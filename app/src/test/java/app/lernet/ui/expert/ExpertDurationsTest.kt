package app.lernet.ui.expert

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertDurationsTest {
    @Test
    fun importedSubminuteValuesRemainExactDuringRenameAndWarmToggle() {
        assertThat(expertSecondsInput(1_500)).isEqualTo("1.5")
        assertThat(expertIdleMilliseconds(expertSecondsInput(1_500))).isEqualTo(1_500)
        assertThat(expertIdleMilliseconds("1,5")).isEqualTo(1_500)
        assertThat(expertIdleAfterEdit(1_500, false, "invalid")).isEqualTo(1_500)
        assertThat(expertIdleAfterEdit(1_500, true, "1.5")).isEqualTo(1_500)
    }

    @Test
    fun contractBoundsAndMillisecondPrecisionAreValidatedWithoutRounding() {
        assertThat(expertIdleMilliseconds("1")).isEqualTo(1_000)
        assertThat(expertIdleMilliseconds("86400")).isEqualTo(86_400_000)
        listOf("0.999", "86400.001", "1.0001", "NaN", "", "-1").forEach {
            assertThat(expertIdleMilliseconds(it)).isNull()
        }
    }

    @Test
    fun healthEditorValidatesOrderingAndActiveTimeoutSeparatelyFromManualChecks() {
        assertThat(expertHealthAfterEdit("3", "7", "4", "2")).isNotNull()
        assertThat(expertHealthAfterEdit("7", "3", "4", "2")).isNull()
        assertThat(expertHealthAfterEdit("3", "61", "4", "2")).isNull()
        assertThat(expertHealthAfterEdit("3", "7", "30", "2")).isNull()
        assertThat(expertHealthAfterEdit("3", "7", "4", "0")).isNull()
    }
}
