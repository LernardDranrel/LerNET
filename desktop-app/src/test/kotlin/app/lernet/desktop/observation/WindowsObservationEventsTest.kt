package app.lernet.desktop.observation

import app.lernet.engine.net.observation.SourceState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class WindowsObservationEventsTest {
    @Test fun `partial logs preserve useful block evidence and denied channel status`() {
        val reader = WindowsObservationEvents(ObservationCommandRunner { _, _ -> error("No command permitted in parser test") })
        val source = reader.parse("""{"channels":[{"name":"System","state":"AVAILABLE","detail":"ok"},{"name":"Security","state":"ACCESS_DENIED","detail":"no access"}],"rows":[{"id":"System:17","title":"block","fields":{"FilterRTID":"123","Application":"C:\\app.exe"}}]}""", now = 123)
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.rows.first().fields["FilterRTID"]).isEqualTo("123")
        assertThat(source.rows.last().fields["Статус источника"]).isEqualTo("ACCESS_DENIED")
        assertThat(source.capturedAt).isEqualTo(123)
        assertThat(source.detail).contains("Security")
    }

    @Test fun `per channel event cap prevents complete snapshot even below global cap`() {
        val reader = WindowsObservationEvents(ObservationCommandRunner { _, _ -> error("No command") })
        val source = reader.parse("""{"channels":[{"name":"System","state":"AVAILABLE","detail":"Last 80 events","complete":false}],"rows":[{"id":"System:1","title":"event","fields":{}}]}""")
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.complete).isFalse()
        val complete = reader.parse("""{"channels":[{"name":"System","state":"AVAILABLE","detail":"ok","complete":true}],"rows":[{"id":"System:1","title":"event","fields":{}}]}""")
        assertThat(complete.complete).isTrue()
    }

    @Test fun `no access is not an empty successful journal`() {
        val reader = WindowsObservationEvents(ObservationCommandRunner { _, _ -> error("No command") })
        assertThat(reader.parse("""{"channels":[{"name":"Security","state":"ACCESS_DENIED","detail":"denied"}],"rows":[]}""").state)
            .isEqualTo(SourceState.ACCESS_DENIED)
        assertThat(reader.parse("""{"channels":[{"name":"System","state":"EMPTY","detail":"none"}],"rows":[]}""").state)
            .isEqualTo(SourceState.EMPTY)
    }

    @Test fun `timeout and malformed output remain explicit failures`() {
        val timeout = WindowsObservationEvents(ObservationCommandRunner { _, _ -> ObservationCommandResult(null, "", true) })
        assertThat(timeout.read().state).isEqualTo(SourceState.TIMEOUT)
        val malformed = WindowsObservationEvents(ObservationCommandRunner { _, _ -> ObservationCommandResult(0, "not JSON") })
        assertThat(malformed.read().state).isEqualTo(SourceState.ERROR)
    }

    @Test fun `journal command never enables auditing or collects arbitrary messages`() {
        var script = ""
        val reader = WindowsObservationEvents(ObservationCommandRunner { args, timeout ->
            assertThat(args[1]).isEqualTo("-NoProfile")
            assertThat(timeout).isEqualTo(35_000)
            script = String(Base64.getDecoder().decode(args.last()), StandardCharsets.UTF_16LE)
            ObservationCommandResult(0, """{"channels":[],"rows":[]}""")
        })
        reader.read()
        assertThat(script).contains("5157")
        assertThat(script).contains("AddMinutes(-30)")
        assertThat(script).contains("log.ProviderNames -contains")
        assertThat(script).contains("found.Count -lt 80")
        assertThat(script).doesNotContain("auditpol")
        assertThat(script).doesNotContain("wevtutil")
        assertThat(script).doesNotContain(".Message")
        assertThat(script).doesNotContain("CommandLine")
        assertThat(script).doesNotContain("Enable-WinEvent")
        assertThat(script).doesNotContain("Set-WinEvent")
    }
}
