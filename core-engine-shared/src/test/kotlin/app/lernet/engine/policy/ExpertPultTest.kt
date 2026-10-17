package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertPultTest {
    @Test fun `lever uptime and lines never contradict each other`() {
        for (phase in PultPhase.entries) for (health in ExpertTunnelHealth.entries) {
            val status = ExpertPult.status(phase, health)
            val lever = ExpertPult.lever(status)
            val line = ExpertPult.schemaLine(status, unsavedDraft = false, unapplied = false, applied = true)
            val head = ExpertPult.headline(status)
            if (lever == "ВКЛ" || ExpertPult.showsUptime(status)) {
                assertFalse("$phase/$health: $line", line.contains("выключ", ignoreCase = true) || line.contains("включишь"))
                assertFalse("$phase/$health: $head", head.contains("проверяем", ignoreCase = true))
            }
            if (ExpertPult.showsUptime(status)) assertTrue(phase == PultPhase.RUNNING || phase == PultPhase.APPLYING)
        }
    }

    @Test fun `checking is not on and does not count uptime`() {
        val status = ExpertPult.status(PultPhase.RUNNING, ExpertTunnelHealth.PENDING)
        assertEquals(PultStatus.CHECKING, status)
        assertEquals("ПРОВЕРКА", ExpertPult.lever(status))
        assertFalse(ExpertPult.showsUptime(status))
    }

    @Test fun `stopped schema line says when it will work`() {
        assertEquals("Схема сохранена · заработает, когда включишь",
            ExpertPult.schemaLine(PultStatus.OFF, unsavedDraft = false, unapplied = false, applied = false))
    }

    @Test fun `working pult does not repeat itself`() {
        assertEquals(null, ExpertPult.schemaNote(PultStatus.WORKING, unsavedDraft = false, unapplied = false, applied = true))
        assertEquals("Есть изменения · применятся после «Применить»",
            ExpertPult.schemaNote(PultStatus.WORKING, unsavedDraft = false, unapplied = true, applied = true))
        assertEquals("Схема работает",
            ExpertPult.schemaNote(PultStatus.HOLDING, unsavedDraft = false, unapplied = false, applied = true))
    }
}
