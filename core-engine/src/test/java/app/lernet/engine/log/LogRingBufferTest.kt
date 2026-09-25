package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogRingBufferTest {
    @Test
    fun evictsOldestWhenOverMaxLines() {
        val buffer = LogRingBuffer(maxLines = 3, maxBytes = 64 * 1024, now = { "t" })
        buffer.append("I", "tag", "one")
        buffer.append("I", "tag", "two")
        buffer.append("I", "tag", "three")
        buffer.append("I", "tag", "four")
        val text = buffer.snapshot()
        assertThat(text).doesNotContain("one")
        assertThat(text).contains("two")
        assertThat(text).contains("four")
        assertThat(buffer.lineCount()).isEqualTo(3)
    }

    @Test
    fun evictsOldestWhenOverMaxBytes() {
        val buffer = LogRingBuffer(maxLines = 50, maxBytes = 40, now = { "t" })
        buffer.append("I", "tag", "aaaaaaaaaa")
        buffer.append("I", "tag", "bbbbbbbbbb")
        buffer.append("I", "tag", "cccccccccc")
        val text = buffer.snapshot()
        assertThat(text).doesNotContain("aaaaaaaaaa")
        assertThat(text.toByteArray(Charsets.UTF_8).size).isAtMost(40)
    }

    @Test
    fun crashLastIncludesThreadTimestampStackAndCrumb() {
        val last = CrashReport.renderLast(
            timestamp = "2026-09-21T09:00:00Z",
            threadName = "main",
            stack = "java.lang.IllegalStateException: boom\n\tat app.lernet.Foo.bar(Foo.kt:1)",
            crumb = "before checkConfig 1200 bytes mode=FULL_VPN stack=omit",
        )
        assertThat(last).contains("=== CRASH-LAST ===")
        assertThat(last).contains("ts=2026-09-21T09:00:00Z")
        assertThat(last).contains("thread=main")
        assertThat(last).contains("IllegalStateException")
        assertThat(last).contains("=== LAST CRUMB ===")
        assertThat(last).contains("before checkConfig")
    }

    @Test
    fun crashReportIncludesStackAndBoundedLogs() {
        val buffer = LogRingBuffer(maxLines = 10, maxBytes = 4096, now = { "t" })
        buffer.append("I", "LerNet.Host", "host.start FULL_VPN")
        val report = CrashReport.render(
            threadName = "main",
            stack = "java.lang.IllegalStateException: boom\n\tat app.lernet.vpn.Foo.bar(Foo.kt:1)",
            logs = buffer.snapshot(),
        )
        assertThat(report).contains("=== CRASH ===")
        assertThat(report).contains("thread=main")
        assertThat(report).contains("IllegalStateException")
        assertThat(report).contains("=== LOGS ===")
        assertThat(report).contains("host.start FULL_VPN")
    }

    @Test
    fun snapshotDoesNotGrowPastCapAfterManyWrites() {
        val buffer = LogRingBuffer(maxLines = 400, maxBytes = 256 * 1024, now = { "t" })
        repeat(1_200) { index ->
            buffer.append("I", "tag", "line-$index-${"x".repeat(80)}")
        }
        assertThat(buffer.lineCount()).isAtMost(400)
        assertThat(buffer.snapshot().toByteArray(Charsets.UTF_8).size).isAtMost(256 * 1024)
    }
}
