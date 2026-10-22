package app.lernet.engine.policy

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPenaltiesTest {
    private val min = 60_000L

    @Test fun restGrowsTwoTenThirtyMinutesWithJitter() {
        val penalties = ConfigPenalties(random = Random(3))
        val first = penalties.fail("a", 0)
        assertTrue(first.untilMs in (2 * min * 8 / 10)..(2 * min * 12 / 10))
        val second = penalties.fail("a", first.untilMs)
        assertTrue(second.untilMs - first.untilMs in (10 * min * 8 / 10)..(10 * min * 12 / 10))
        val third = penalties.fail("a", second.untilMs)
        val fourth = penalties.fail("a", third.untilMs)
        assertEquals(3, fourth.level)
        assertTrue(fourth.untilMs - third.untilMs in (30 * min * 8 / 10)..(30 * min * 12 / 10))
    }

    @Test fun restingIsNotClearedByAnythingButAWorkingConfig() {
        val penalties = ConfigPenalties(random = Random(3))
        penalties.fail("a", 0)
        assertTrue(penalties.isResting("a", 60_000))
        assertEquals(setOf("a"), penalties.resting(60_000))
        penalties.clear("a")
        assertFalse(penalties.isResting("a", 60_000))
        assertNull(penalties.entry("a"))
    }

    @Test fun listSurvivesARestartAndOldEntriesAreForgotten() {
        val penalties = ConfigPenalties(random = Random(3))
        penalties.fail("a", 0)
        penalties.fail("b", 0)
        penalties.fail("b", 1_000)
        val restored = ConfigPenalties()
        restored.decode(penalties.encode(), 30_000)
        assertTrue(restored.isResting("a", 30_000))
        assertEquals(2, restored.entry("b")!!.level)
        val later = ConfigPenalties()
        later.decode(penalties.encode(), ConfigPenalties.FORGET_MS + 5_000)
        assertNull(later.entry("a"))
        // Broken lines are skipped, not fatal.
        later.decode("x\nb 2 abc 0\nc 1 100 0", 50)
        assertEquals(setOf("c"), later.resting(50))
    }

    @Test fun clockJumpCannotParkAConfigForever() {
        val restored = ConfigPenalties()
        restored.decode("a 3 ${Long.MAX_VALUE / 2} 0", 1_000)
        assertTrue(restored.restMs("a", 1_000) <= 2 * ConfigPenalties.LADDER_MS.last())
    }

    @Test fun aNewNetworkLetsRestingConfigsTryOnceMoreButKeepsTheLevel() {
        val penalties = ConfigPenalties(random = Random(3))
        penalties.fail("a", 0)
        penalties.fail("a", 0)
        assertTrue(penalties.onNetworkChanged(10_000))
        assertFalse(penalties.isResting("a", 10_000))
        assertEquals(2, penalties.entry("a")!!.level)
        // Rate-limited: a flapping network does not keep releasing.
        penalties.fail("a", 20_000)
        assertFalse(penalties.onNetworkChanged(30_000))
        assertTrue(penalties.isResting("a", 30_000))
    }

    @Test fun everyChangeIsHandedToPersistence() {
        val saved = mutableListOf<String>()
        val penalties = ConfigPenalties(random = Random(3))
        penalties.onChange = { saved += it }
        penalties.fail("a", 0)
        penalties.clear("a")
        penalties.clear("a")
        assertEquals(2, saved.size)
        assertEquals("", saved.last())
    }

    @Test fun nextFreeAtIsTheSoonestRestingEnd() {
        val penalties = ConfigPenalties(ladderMs = listOf(100L, 1_000L), random = Random(3))
        penalties.fail("a", 0)
        penalties.fail("b", 0)
        penalties.fail("b", 0)
        val soonest = penalties.nextFreeAt(listOf("a", "b", "c"), 0)!!
        assertEquals(penalties.entry("a")!!.untilMs, soonest)
        assertNull(penalties.nextFreeAt(listOf("c"), 0))
    }
}
