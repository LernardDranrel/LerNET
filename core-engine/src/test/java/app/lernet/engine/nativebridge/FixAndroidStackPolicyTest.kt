package app.lernet.engine.nativebridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FixAndroidStackPolicyTest {
    @Test
    fun enabledOnNAndNmr1EvenWhenNotDebug() {
        assertThat(FixAndroidStackPolicy.enabled(FixAndroidStackPolicy.API_N, debug = false)).isTrue()
        assertThat(FixAndroidStackPolicy.enabled(FixAndroidStackPolicy.API_N_MR1, debug = false)).isTrue()
    }

    @Test
    fun enabledOnPAndAboveEvenWhenNotDebug() {
        assertThat(FixAndroidStackPolicy.enabled(FixAndroidStackPolicy.API_P, debug = false)).isTrue()
        assertThat(FixAndroidStackPolicy.enabled(36, debug = false)).isTrue()
    }

    @Test
    fun disabledOnOreoWhenNotDebug() {
        assertThat(FixAndroidStackPolicy.enabled(26, debug = false)).isFalse()
        assertThat(FixAndroidStackPolicy.enabled(27, debug = false)).isFalse()
    }

    @Test
    fun debugForcesEnabledOnEverySdk() {
        assertThat(FixAndroidStackPolicy.enabled(26, debug = true)).isTrue()
        assertThat(FixAndroidStackPolicy.enabled(1, debug = true)).isTrue()
    }
}
