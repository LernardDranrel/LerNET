package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternSignTest {
    @Test
    fun typingBangTurnsNegationOnAndBodyStaysPlain() {
        val stored = PatternSign.retainSign("example.com", "!example.com")
        assertThat(stored).isEqualTo("!example.com")
        assertThat(PatternSign.body(stored)).isEqualTo("example.com")
        assertThat(PatternSign.negated(stored)).isTrue()
    }

    @Test
    fun signingOffStripsTheBang() {
        assertThat(PatternSign.signed("!*.evil.com", negated = false)).isEqualTo("*.evil.com")
    }
}
