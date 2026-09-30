package app.lernet.desktop.observation

import app.lernet.engine.net.observation.SourceState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Executes only fixture parsers and mocked Get-WinEvent. Never invokes netsh or real journals. */
class WindowsObservationPowerShellTest {
    @get:Rule val temp = TemporaryFolder()

    private fun execute(script: String): ObservationCommandResult {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        return SystemObservationCommandRunner.run(powershellArguments(script), 10_000)
    }

    private fun wfpFunctions(): String = requireNotNull(javaClass.getResourceAsStream("/observation.ps1"))
        .bufferedReader().use { it.readText() }.substringBefore("# Collect allowlisted sources only.")

    @Test fun `stderr diagnostics never contaminate successful JSON`() {
        val result = execute("""
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new(${ '$' }false)
            [Console]::Error.WriteLine('fixture diagnostics')
            Write-Output '{"state":"EMPTY","rows":[],"detail":"fixture"}'
        """.trimIndent())
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.errorOutput).contains("fixture diagnostics")
        assertThat(WindowsNetworkObservation.parseSource(WindowsObservationDefinition("wfp", "WFP", ""), result).state)
            .isEqualTo(SourceState.EMPTY)
    }

    @Test fun `oversized response is reported instead of parsing clipped JSON`() {
        val result = execute("[Console]::Out.Write(('x' * (5 * 1024 * 1024)))")
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.outputTruncated).isTrue()
        assertThat(result.output.length).isEqualTo(4 * 1024 * 1024)
        assertThat(observationOutputProblem(result)).contains("4 МБ")
    }

    @Test fun `WFP parser reads Unicode UTF8 and UTF16 XML with namespaces and nested weights`() {
        val xml = """<?xml version="1.0" encoding="ENCODING"?>
            <wfp xmlns="urn:fixture"><providers><item><providerKey>provider</providerKey><displayData><name>Сетевой фильтр</name></displayData></item></providers>
            <filters><item><filterId>42</filterId><filterKey>filter</filterKey><providerKey>provider</providerKey>
            <displayData><name>Правило приложения</name></displayData><layerKey>layer</layerKey>
            <action><type>FWP_ACTION_BLOCK</type></action><weight><type>FWP_UINT64</type><uint64>10</uint64></weight>
            <filterCondition><item><fieldKey>FWPM_CONDITION_IP_PROTOCOL</fieldKey><matchType>FWP_MATCH_EQUAL</matchType><conditionValue><uint8>6</uint8></conditionValue></item></filterCondition>
            </item></filters></wfp>""".trimIndent()
        for ((encoding, charset) in listOf("utf-8" to StandardCharsets.UTF_8, "utf-16" to StandardCharsets.UTF_16,
            "utf-16" to StandardCharsets.UTF_16LE)) {
            val file = temp.newFile("fixture-${charset.name()}.xml")
            file.writeText(xml.replace("ENCODING", encoding), charset)
            val path = Base64.getEncoder().encodeToString(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
            val result = execute(wfpFunctions() + "\n" + """
                ${'$'}fixturePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$path'))
                @{rows=@(Read-WfpState ${'$'}fixturePath)} | ConvertTo-Json -Depth 6 -Compress
            """.trimIndent())
            assertThat(result.exitCode).isEqualTo(0)
            val row = Json.parseToJsonElement(result.output).jsonObject["rows"]!!.jsonArray.single().jsonObject
            assertThat(row["ProviderName"]!!.jsonPrimitive.content).isEqualTo("Сетевой фильтр")
            assertThat(row["Name"]!!.jsonPrimitive.content).isEqualTo("Правило приложения")
            assertThat(row["Action"]!!.jsonPrimitive.content).isEqualTo("FWP_ACTION_BLOCK")
            assertThat(row["Conditions"]!!.jsonArray.single().jsonPrimitive.content).contains("FWP_MATCH_EQUAL 6")
        }
    }

    @Test fun `unexpected XML and external DTD are failures instead of empty successful WFP`() {
        for ((index, xml) in listOf("<differentDocument/>",
            """<!DOCTYPE wfp [<!ENTITY external SYSTEM "file:///fixture-must-never-be-read">]><wfp><filters>&external;</filters></wfp>""").withIndex()) {
            val file = temp.newFile("invalid-$index.xml")
            file.writeText(xml)
            val path = Base64.getEncoder().encodeToString(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
            val result = execute(wfpFunctions() + "\n" + """
                ${'$'}fixturePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$path'))
                try { ${'$'}fixtureRows = @(Read-WfpState ${'$'}fixturePath); @{accepted=${'$'}true} | ConvertTo-Json -Compress }
                catch { @{accepted=${'$'}false;detail=(Read-FailureDetail ${'$'}_)} | ConvertTo-Json -Compress }
            """.trimIndent())
            val envelope = Json.parseToJsonElement(result.output).jsonObject
            assertThat(envelope["accepted"]!!.jsonPrimitive.boolean).isFalse()
            val detail = envelope["detail"]!!.jsonPrimitive.content
            if (index == 1) assertThat(detail).contains("XmlException")
        }
    }

    @Test fun `malformed WFP XML has bounded diagnostic without dumping file contents`() {
        val file = temp.newFile("broken.xml")
        file.writeText("<wfp><private>fixture-private-marker" + "x".repeat(100_000))
        val path = Base64.getEncoder().encodeToString(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
        val result = execute(wfpFunctions() + "\n" + """
            ${'$'}fixturePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$path'))
            try { Read-WfpState ${'$'}fixturePath } catch { @{detail=(Read-FailureDetail ${'$'}_)} | ConvertTo-Json -Compress }
        """.trimIndent())
        val detail = Json.parseToJsonElement(result.output).jsonObject["detail"]!!.jsonPrimitive.content
        assertThat(detail).contains("XmlException")
        assertThat(detail).contains("line=")
        assertThat(detail).doesNotContain("fixture-private-marker")
        assertThat(detail.length).isAtMost(1024)
    }

    @Test fun `event script returns mocked events and explains denied channels on Windows PowerShell`() {
        val mock = """
            function Get-WinEvent {
                param(${ '$' }ListLog, ${ '$' }FilterHashtable, ${ '$' }MaxEvents, ${ '$' }ErrorAction)
                if (${ '$' }ListLog -eq 'Security') { throw [UnauthorizedAccessException]::new('fixture-private-marker') }
                if (${ '$' }ListLog) { return [pscustomobject]@{IsEnabled=(${ '$' }ListLog -eq 'System'); ProviderNames=@('Microsoft-Windows-TCPIP')} }
                ${ '$' }event = [pscustomobject]@{TimeCreated=[datetime]'2026-01-01'; ProviderName='Microsoft-Windows-TCPIP'; Id=1; RecordId=42; Message='fixture-private-marker'}
                ${ '$' }event | Add-Member -MemberType ScriptMethod -Name ToXml -Value { '<Event><System><Execution ProcessID="123"/></System><EventData><Data Name="SourceAddress">192.0.2.1</Data><Data Name="CommandLine">fixture-private-marker</Data></EventData></Event>' }
                return ${ '$' }event
            }
        """.trimIndent()
        val result = execute(mock + "\n" + WindowsObservationEvents.EVENTS_SCRIPT)
        assertThat(result.exitCode).isEqualTo(0)
        val source = WindowsObservationEvents().parse(result.output)
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.rows.first().fields["SourceAddress"]).isEqualTo("192.0.2.1")
        assertThat(source.rows.single { it.title == "Security" }.fields["Описание"]).contains("HRESULT=")
        assertThat(result.output).doesNotContain("fixture-private-marker")
        assertThat(source.complete).isFalse()
    }
}
