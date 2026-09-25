package app.lernet.engine.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ByteGrowthGateTest {
    @Test
    fun lifetimeTotalsAreNotAliveOnceFlat() {
        val gate = ByteGrowthGate()
        assertThat(gate.observed(0, 0, 10_861, 13_202)).isTrue()
        assertThat(gate.observed(0, 0, 10_861, 13_202)).isFalse()
    }

    @Test
    fun growthOrRateHolds() {
        val gate = ByteGrowthGate()
        assertThat(gate.observed(0, 0, 100, 0)).isTrue()
        assertThat(gate.observed(0, 0, 150, 20)).isTrue()
        assertThat(gate.observed(64, 0, 150, 20)).isTrue()
        assertThat(gate.observed(0, 0, 150, 20)).isFalse()
    }

    @Test
    fun resetAllowsGrowthFromZeroAgain() {
        val gate = ByteGrowthGate()
        assertThat(gate.observed(0, 0, 500, 500)).isTrue()
        gate.reset()
        assertThat(gate.observed(0, 0, 0, 0)).isFalse()
        assertThat(gate.observed(0, 0, 40, 10)).isTrue()
    }
}
