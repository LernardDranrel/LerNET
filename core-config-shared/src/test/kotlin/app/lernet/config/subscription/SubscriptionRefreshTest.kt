package app.lernet.config.subscription

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import app.lernet.config.net.RemoteTextFetcher
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportedProfileDraft
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Test

class SubscriptionRefreshTest {
    private fun outbound(id: String, server: String, uuid: String = "u1") =
        NormalizedOutbound(id, "proxy", "vless", """{"type":"vless","tag":"proxy","server":"$server","server_port":443,"uuid":"$uuid"}""")

    private fun draft(name: String, server: String, uuid: String = "u1"): ImportedProfileDraft {
        val o = outbound("new-$name", server, uuid)
        return ImportedProfileDraft(name, ProfileSource.SUBSCRIPTION, listOf(o), o.id, "https://sub")
    }

    private fun config(id: String, name: String, server: String, uuid: String = "u1", selected: Boolean = false, own: Boolean = false) =
        SubscribedConfig(id, name, listOf(outbound("o-$id", server, uuid)), "o-$id", selected, own)

    private fun origin(c: SubscribedConfig) = SubscriptionOrigin(c.name, SubscriptionRefresh.hashOf(c.outbounds))

    @Test fun newKeyKeepsIdSelectionAndOutboundId() {
        val de = config("a", "Германия", "de.example", selected = true)
        val plan = SubscriptionRefresh.plan(listOf(de), listOf(draft("Германия", "de.example", uuid = "u2")), mapOf("a" to origin(de)))
        val update = plan.updates.single()
        assertThat(update.id).isEqualTo("a")
        assertThat(update.selectedOutboundId).isEqualTo("o-a")
        assertThat(update.outbounds.single().id).isEqualTo("o-a")
        assertThat(update.outbounds.single().singBoxJson).contains("u2")
        assertThat(update.name).isNull()
        assertThat(plan.adds).isEmpty()
        assertThat(plan.removes).isEmpty()
    }

    @Test fun serverMovedButNameStaysIsMatchedByName() {
        val de = config("a", "Германия", "old.example")
        val plan = SubscriptionRefresh.plan(listOf(de), listOf(draft("Германия", "new.example")), mapOf("a" to origin(de)))
        assertThat(plan.updates.single().outbounds.single().singBoxJson).contains("new.example")
    }

    @Test fun renamedByProviderIsMatchedByServerAndRenamed() {
        val de = config("a", "DE", "de.example")
        val plan = SubscriptionRefresh.plan(listOf(de), listOf(draft("Германия 1", "de.example")), mapOf("a" to origin(de)))
        assertThat(plan.updates.single().name).isEqualTo("Германия 1")
    }

    @Test fun userRenameIsKept() {
        val imported = config("a", "DE", "de.example")
        val renamed = imported.copy(name = "Мой сервер")
        val plan = SubscriptionRefresh.plan(listOf(renamed), listOf(draft("DE", "de.example", uuid = "u2")), mapOf("a" to origin(imported)))
        assertThat(plan.updates.single().name).isNull()
        assertThat(plan.origins.getValue("a").name).isEqualTo("DE")
    }

    @Test fun userEditedServerDataIsLeftAlone() {
        val imported = config("a", "DE", "de.example")
        val edited = imported.copy(outbounds = listOf(outbound("o-a", "my.example")))
        val plan = SubscriptionRefresh.plan(listOf(edited), listOf(draft("DE", "de.example", uuid = "u2")), mapOf("a" to origin(imported)))
        assertThat(plan.updates).isEmpty()
        assertThat(plan.keptEdited).containsExactly("a")
        assertThat(plan.summary(null)).contains("с вашими правками не тронуты: 1")
    }

    @Test fun goneConfigsAreRemovedUnlessChosenOrTuned() {
        val keep = config("keep", "A", "a.example")
        val chosen = config("chosen", "B", "b.example", selected = true)
        val tuned = config("tuned", "C", "c.example", own = true)
        val plain = config("plain", "D", "d.example")
        val all = listOf(keep, chosen, tuned, plain)
        val fresh = listOf(draft("A", "a.example"), draft("E", "e.example"))
        val plan = SubscriptionRefresh.plan(all, fresh, all.associate { it.id to origin(it) })
        assertThat(plan.removes).containsExactly("plain")
        assertThat(plan.keptGone).containsExactly("chosen", "tuned")
        assertThat(plan.adds.map { it.name }).containsExactly("E")
        assertThat(plan.unchanged).isEqualTo(1)
        assertThat(plan.removalHeld).isFalse()
    }

    @Test fun muchShorterAnswerRemovesNothing() {
        val all = (1..6).map { config("c$it", "S$it", "s$it.example") }
        val plan = SubscriptionRefresh.plan(all, listOf(draft("Подписка истекла", "0.0.0.0")), all.associate { it.id to origin(it) })
        assertThat(plan.removes).isEmpty()
        assertThat(plan.removalHeld).isTrue()
        assertThat(plan.summary(null)).contains("старые конфиги не удалены")
    }

    @Test fun configsFromOlderVersionWithoutOriginKeepTheirNameButGetNewData() {
        val old = config("a", "Мой DE", "de.example")
        val plan = SubscriptionRefresh.plan(listOf(old), listOf(draft("DE", "de.example", uuid = "u9")), emptyMap())
        assertThat(plan.updates.single().name).isNull()
        assertThat(plan.updates.single().outbounds.single().singBoxJson).contains("u9")
        assertThat(plan.origins.getValue("a").name).isEqualTo("DE")
    }

    @Test fun hashIgnoresKeyOrderAndTag() {
        val a = NormalizedOutbound("1", "proxy", "vless", """{"type":"vless","tag":"proxy","server":"h","server_port":1}""")
        val b = NormalizedOutbound("2", "x", "vless", """{"server_port":1,"server":"h","tag":"other","type":"vless"}""")
        assertThat(SubscriptionRefresh.hashOf(listOf(a))).isEqualTo(SubscriptionRefresh.hashOf(listOf(b)))
    }

    @Test fun scheduleUsesHeaderOrTwelveHoursWithShift() {
        assertThat(SubscriptionSchedule.headerHours("6")).isEqualTo(6)
        assertThat(SubscriptionSchedule.headerHours("0.5")).isEqualTo(1)
        assertThat(SubscriptionSchedule.headerHours("abc")).isNull()
        assertThat(SubscriptionSchedule.headerHours("100000")).isEqualTo(SubscriptionSchedule.MAX_HOURS)
        val entry = SubscriptionEntry("u", lastSuccessMs = 1_000L, jitterMs = 5L)
        assertThat(SubscriptionSchedule.dueAt(entry)).isEqualTo(1_000L + 12 * 3_600_000L + 5L)
        assertThat(SubscriptionSchedule.dueAt(entry.copy(intervalHours = 2))).isEqualTo(1_000L + 2 * 3_600_000L + 5L)
        val failed = entry.copy(failedSinceSuccess = true, lastAttemptMs = 50_000L)
        assertThat(SubscriptionSchedule.dueAt(failed)).isEqualTo(50_000L + SubscriptionSchedule.RETRY_MS + 5L)
        repeat(50) { assertThat(SubscriptionSchedule.jitter()).isIn(com.google.common.collect.Range.closed(0L, SubscriptionSchedule.MAX_JITTER_MS)) }
    }

    @Test fun updaterRemembersOriginsIntervalAndFailures() {
        val dir = Files.createTempDirectory("sub-book")
        val book = SubscriptionBook(dir.resolve("subscriptions.json"))
        var body = "trojan://pw@t.example.com:443#T"
        var now = 10_000L
        val coordinator = ImportCoordinator { RemoteTextFetcher.Result.Ok(body, "text/plain", mapOf("profile-update-interval" to "3")) }
        val updater = SubscriptionUpdater(coordinator, book, now = { now }, random = Random(1))
        val first = updater.prepare("https://sub", emptyList()) as SubscriptionUpdater.Prepared.Ready
        assertThat(first.plan.adds).hasSize(1)
        updater.applied(first, listOf("id-1"))
        val entry = book.entry("https://sub")!!
        assertThat(entry.intervalHours).isEqualTo(3)
        assertThat(entry.origins.keys).containsExactly("id-1")
        assertThat(updater.due(setOf("https://sub"))).isEmpty()
        now += 3 * 3_600_000L + SubscriptionSchedule.MAX_JITTER_MS
        assertThat(updater.due(setOf("https://sub"))).containsExactly("https://sub")
        body = ""
        val failed = updater.prepare("https://sub", emptyList())
        assertThat(failed).isInstanceOf(SubscriptionUpdater.Prepared.Failed::class.java)
        assertThat(book.entry("https://sub")!!.failedSinceSuccess).isTrue()
        assertThat(updater.due(setOf("https://sub"))).isEmpty()
    }

    @Test fun subscriptionImportedBeforeRefreshesStartsItsScheduleWithoutAFetch() {
        val dir = Files.createTempDirectory("sub-book")
        val book = SubscriptionBook(dir.resolve("subscriptions.json"))
        val updater = SubscriptionUpdater(ImportCoordinator { error("must not fetch") }, book, now = { 5L })
        assertThat(updater.due(setOf("https://old"))).isEmpty()
        assertThat(book.entry("https://old")!!.lastSuccessMs).isEqualTo(5L)
    }
}
