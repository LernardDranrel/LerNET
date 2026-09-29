package app.lernet.desktop.observation

import app.lernet.engine.net.observation.EvidenceRow
import app.lernet.engine.net.observation.ObservationSource
import app.lernet.engine.net.observation.SourceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Existing journals only: this reader never enables a log, auditing, or network activity. */
internal class WindowsObservationEvents(
    private val runner: ObservationCommandRunner = SystemObservationCommandRunner,
    private val systemDirectory: File = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32"),
) {
    fun read(now: Long = System.currentTimeMillis()): ObservationSource = execute(
        EVENTS_SCRIPT, now, "events", "Что заметила Windows",
        "События за последние 30 минут. Windows записывает только включённые журналы; отсутствие записи не доказывает отсутствие сбоя.",
    )

    fun readTrace(file: File, now: Long = System.currentTimeMillis()): ObservationSource {
        // The path is encoded as data, never interpolated into PowerShell syntax.
        val pathData = Base64.getEncoder().encodeToString(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
        val script = "\$tracePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$pathData'))\n" +
            COMMON_SCRIPT + "\n" + TRACE_SCRIPT
        return execute(script, now, "trace-events", "События короткой записи",
            "События TCP/IP из локального ETL. Видны только поля, которые предоставил источник Windows; это не расшифровка трафика.")
    }

    private fun execute(script: String, now: Long, id: String, title: String, explanation: String): ObservationSource {
        val result = try {
            runner.run(listOf(File(systemDirectory, "WindowsPowerShell/v1.0/powershell.exe").absolutePath,
                "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))), 35_000)
        } catch (error: Exception) {
            return ObservationSource(id, title, explanation, SourceState.ERROR,
                detail = "Не удалось прочитать источник: ${error.javaClass.simpleName}", capturedAt = now)
        }
        if (result.timedOut) return ObservationSource(id, title, explanation, SourceState.TIMEOUT,
            detail = "Чтение журнала заняло больше 35 секунд.", capturedAt = now)
        if (result.exitCode != 0) return ObservationSource(id, title, explanation, SourceState.ERROR,
            detail = "Windows не вернула журнал (код ${result.exitCode ?: "неизвестен"}).", capturedAt = now)
        return parse(result.output, id, title, explanation, now)
    }

    internal fun parse(output: String, id: String = "events", title: String = "Что заметила Windows",
        explanation: String = "Существующие события Windows", now: Long = System.currentTimeMillis()): ObservationSource {
        return try {
            val envelope = Json.parseToJsonElement(output.trim().removePrefix("\uFEFF")) as JsonObject
            val rows = (envelope["rows"] as? JsonArray).orEmpty().take(250).mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                val fields = (row["fields"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content.take(1_024) }.orEmpty()
                EvidenceRow(row.string("id"), row.string("title"), fields)
            }
            val statuses = (envelope["channels"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val available = statuses.count { it.string("state") in setOf("AVAILABLE", "EMPTY") }
            val missing = statuses.filter { it.string("state") !in setOf("AVAILABLE", "EMPTY") }
            val complete = missing.isEmpty() && rows.size < 250 && statuses.none {
                it["complete"]?.jsonPrimitive?.booleanOrNull == false
            }
            val status = when {
                available > 0 -> if (rows.isEmpty()) SourceState.EMPTY else SourceState.AVAILABLE
                statuses.isNotEmpty() && statuses.all { it.string("state") == "ACCESS_DENIED" } -> SourceState.ACCESS_DENIED
                statuses.isNotEmpty() && statuses.all { it.string("state") == "UNSUPPORTED" } -> SourceState.UNSUPPORTED
                else -> SourceState.ERROR
            }
            val detail = if (missing.isEmpty()) "Прочитано источников: $available. Показано событий: ${rows.size} (выборка ограничена)." else
                "Прочитано источников: $available. Недоступны: " + missing.joinToString("; ") { "${it.string("name")}: ${it.string("detail")}" }
            val channelRows = statuses.map { channel -> EvidenceRow("channel:${channel.string("name")}", channel.string("name"),
                mapOf("Статус источника" to channel.string("state"), "Описание" to channel.string("detail"))) }
            ObservationSource(id, title, explanation, status, rows + channelRows, detail, now, complete = complete)
        } catch (_: Exception) {
            ObservationSource(id, title, explanation, SourceState.ERROR,
                detail = "Windows вернула журнал в неожиданном формате.", capturedAt = now)
        }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

    companion object {
        // Do not use Message: arbitrary event text can contain command lines or secrets.
        private val COMMON_SCRIPT = """
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new()
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}rows = [Collections.Generic.List[object]]::new()
            ${'$'}channels = [Collections.Generic.List[object]]::new()
            ${'$'}allowFields = @('ProcessId','ProcessID','Application','SourceAddress','SourcePort','DestAddress','DestPort',
                'DestinationAddress','DestinationPort','Protocol','FilterRTID','LayerRTID','LayerName','Status','ErrorCode',
                'InterfaceGuid','InterfaceIndex','ReasonCode','FailureReason','RemoteAddress','LocalAddress','LocalPort','RemotePort',
                'saddr','daddr','sport','dport','PID','connid','FailureCode','LocalSockAddr','RemoteSockAddr')
            function Add-SafeEvent(${ '$' }event, ${ '$' }channel) {
                [xml]${'$'}xml = ${'$'}event.ToXml()
                ${'$'}fields = [ordered]@{'Время UTC'=${'$'}event.TimeCreated.ToUniversalTime().ToString('o');
                    'Источник'=[string]${'$'}event.ProviderName; 'Event ID'=[string]${'$'}event.Id; 'Журнал'=${'$'}channel}
                if (${ '$' }xml.Event.System.Execution.ProcessID) { ${ '$' }fields['PID источника'] = [string]${ '$' }xml.Event.System.Execution.ProcessID }
                foreach (${ '$' }item in @(${ '$' }xml.Event.EventData.Data)) {
                    ${'$'}name = [string]${'$'}item.Name
                    if (${ '$' }allowFields -contains ${ '$' }name) { ${ '$' }fields[${ '$' }name] = ([string]${ '$' }item.'#text').Substring(0, [Math]::Min(1024, ([string]${ '$' }item.'#text').Length)) }
                }
                ${'$'}title = [string]${'$'}event.ProviderName + ' · ' + [string]${'$'}event.Id
                if (${ '$' }event.Id -eq 5157 -and ${ '$' }event.ProviderName -eq 'Microsoft-Windows-Security-Auditing') { ${ '$' }title = 'Windows заблокировала соединение · 5157' }
                ${'$'}recordKey = [string]${'$'}event.RecordId
                if (${ '$' }channel -eq 'ETL TCP/IP') { ${ '$' }recordKey = [string]${ '$' }event.TimeCreated.Ticks + ':' + [string]${ '$' }rows.Count }
                ${'$'}rows.Add([ordered]@{id=(${ '$' }channel + ':' + ${ '$' }recordKey); title=${'$'}title; fields=${'$'}fields})
            }
            function Failure-State(${ '$' }errorRecord) {
                if (${ '$' }errorRecord.Exception -is [UnauthorizedAccessException] -or ${ '$' }errorRecord.FullyQualifiedErrorId -match 'Unauthorized|AccessDenied') { return 'ACCESS_DENIED' }
                return 'ERROR'
            }
        """.trimIndent()

        internal val EVENTS_SCRIPT = COMMON_SCRIPT + "\n" + """
            ${'$'}since = (Get-Date).AddMinutes(-30)
            ${'$'}specs = @(
                @{name='System'; providers=@('Microsoft-Windows-TCPIP','Microsoft-Windows-Dhcp-Client','Microsoft-Windows-DNS-Client','RasClient','RasMan','IKEEXT','Schannel')},
                @{name='Application'; providers=@('RasClient','RasMan')},
                @{name='Microsoft-Windows-DNS-Client/Operational'; providers=@()},
                @{name='Microsoft-Windows-Dhcp-Client/Admin'; providers=@()},
                @{name='Microsoft-Windows-WLAN-AutoConfig/Operational'; providers=@()},
                @{name='Microsoft-Windows-RasClient/Operational'; providers=@()},
                @{name='Security'; providers=@(); ids=@(5157)}
            )
            foreach (${ '$' }spec in ${ '$' }specs) {
                try {
                    ${'$'}log = Get-WinEvent -ListLog ${'$'}spec.name -ErrorAction Stop
                    if (-not ${ '$' }log.IsEnabled) { ${ '$' }channels.Add(@{name=${ '$' }spec.name;state='UNSUPPORTED';detail='Журнал отключён; LerNET его не включает.'}); continue }
                    ${'$'}filter = @{LogName=${'$'}spec.name; StartTime=${'$'}since}
                    if (${ '$' }spec.providers.Count -gt 0) {
                        # Get-WinEvent rejects a provider absent from this log. Query only published names.
                        ${ '$' }providers = @(${ '$' }spec.providers | Where-Object { ${ '$' }log.ProviderNames -contains ${ '$' }_ })
                        if (${ '$' }providers.Count -eq 0) {
                            ${ '$' }channels.Add(@{name=${ '$' }spec.name;state='UNSUPPORTED';detail='В журнале нет зарегистрированных сетевых источников.';complete=${ '$' }false}); continue
                        }
                        ${ '$' }filter.ProviderName = ${ '$' }providers
                    }
                    if (${ '$' }spec.ids) { ${ '$' }filter.Id = ${ '$' }spec.ids }
                    try { ${ '$' }found = @(Get-WinEvent -FilterHashtable ${ '$' }filter -MaxEvents 80 -ErrorAction Stop) }
                    catch { if (${ '$' }_.FullyQualifiedErrorId -match 'NoMatchingEventsFound') { ${ '$' }found = @() } else { throw } }
                    foreach (${ '$' }event in ${ '$' }found) { Add-SafeEvent ${ '$' }event ${ '$' }spec.name }
                    ${'$'}channels.Add(@{name=${'$'}spec.name;state=$(if(${ '$' }found.Count -eq 0){'EMPTY'}else{'AVAILABLE'});detail=$(if(${ '$' }found.Count -ge 80){'Последние 80 событий за 30 минут; выборка ограничена.'}else{'За последние 30 минут'});complete=(${ '$' }found.Count -lt 80)})
                } catch {
                    ${'$'}state = Failure-State ${'$'}_
                    if (${ '$' }_.FullyQualifiedErrorId -match 'NoMatchingLogsFound') { ${ '$' }state = 'UNSUPPORTED' }
                    ${'$'}channels.Add(@{name=${'$'}spec.name;state=${'$'}state;detail=$(if(${ '$' }state -eq 'ACCESS_DENIED'){'Не хватает прав для чтения.'}elseif(${ '$' }state -eq 'UNSUPPORTED'){'Журнал отсутствует.'}else{'Windows не предоставила журнал.'})})
                }
            }
            [ordered]@{channels=@(${ '$' }channels); rows=@(${ '$' }rows | Sort-Object { ${ '$' }_.fields['Время UTC'] } -Descending | Select-Object -First 250)} | ConvertTo-Json -Depth 8 -Compress
        """.trimIndent()

        private val TRACE_SCRIPT = """
            try {
                ${'$'}events = @(Get-WinEvent -Path ${'$'}tracePath -Oldest -MaxEvents 250 -ErrorAction Stop)
                foreach (${ '$' }event in ${ '$' }events) { Add-SafeEvent ${ '$' }event 'ETL TCP/IP' }
                ${'$'}channels.Add(@{name='ETL TCP/IP';state=$(if(${ '$' }events.Count -eq 0){'EMPTY'}else{'AVAILABLE'});detail=$(if(${ '$' }events.Count -ge 250){'Первые 250 событий локальной записи; выборка ограничена.'}else{'Локальная короткая запись'});complete=(${ '$' }events.Count -lt 250)})
            } catch {
                ${'$'}state = Failure-State ${'$'}_
                if (${ '$' }_.FullyQualifiedErrorId -match 'NoMatchingEventsFound') { ${ '$' }state = 'EMPTY' }
                ${'$'}channels.Add(@{name='ETL TCP/IP';state=${'$'}state;detail='Windows не декодировала события; исходный ETL сохранён.'})
            }
            [ordered]@{channels=@(${ '$' }channels);rows=@(${ '$' }rows)} | ConvertTo-Json -Depth 8 -Compress
        """.trimIndent()
    }
}
