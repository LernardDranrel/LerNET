package app.lernet.engine.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DnsQueryLogTest {
    @Test
    fun successIncludesAnswerCount() {
        val line = DnsQueryLog.format(
            DnsQueryRecord(
                domain = "example.com",
                queryType = 1,
                failed = false,
                error = null,
                rcode = 0,
                answerCount = 2,
                serverType = "https",
                server = "1.1.1.1",
            ),
        )
        assertThat(line).startsWith("dns query ok")
        assertThat(line).contains("answers=2")
        assertThat(line).contains("serverType=https")
    }

    @Test
    fun failureIncludesErrorString() {
        val line = DnsQueryLog.format(
            DnsQueryRecord(
                domain = "example.com",
                queryType = 1,
                failed = true,
                error = "context canceled",
                rcode = 2,
                answerCount = 0,
                serverType = "tls",
                server = "1.1.1.1",
            ),
        )
        assertThat(line).startsWith("dns query fail")
        assertThat(line).contains("error=context canceled")
        assertThat(line).contains("answers=0")
    }
}
