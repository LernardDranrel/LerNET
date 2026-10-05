package app.lernet.desktop.expert

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertLifecycleValuesTest {
    @Test
    fun `imported millisecond timeouts round trip without changing their precision`() {
        listOf(1_000L, 37_123L, 900_000L, 86_400_000L).forEach { milliseconds ->
            assertThat(ExpertLifecycleValues.milliseconds(ExpertLifecycleValues.seconds(milliseconds), 86_400_000))
                .isEqualTo(milliseconds)
        }
    }

    @Test
    fun `duration fields enforce shared idle and startup bounds`() {
        assertThat(ExpertLifecycleValues.milliseconds("86400", 86_400_000)).isEqualTo(86_400_000)
        assertThat(ExpertLifecycleValues.milliseconds("86400.001", 86_400_000)).isNull()
        assertThat(ExpertLifecycleValues.milliseconds("45", 45_000)).isEqualTo(45_000)
        assertThat(ExpertLifecycleValues.milliseconds("45.001", 45_000)).isNull()
        assertThat(ExpertLifecycleValues.milliseconds("0.999", 45_000)).isNull()
        assertThat(ExpertLifecycleValues.milliseconds("12,123", 45_000)).isEqualTo(12_123)
    }

    @Test
    fun `invalid and unbounded numeric representations stay editable but cannot save`() {
        listOf("", "-1", "1e99999999", "NaN", "1.0001", "999999999999999999999").forEach { value ->
            assertThat(ExpertLifecycleValues.milliseconds(value, 86_400_000)).isNull()
        }
    }
}
