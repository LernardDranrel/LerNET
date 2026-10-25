package app.lernet.desktop

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import app.lernet.config.parse.ImportedProfileDraft
import app.lernet.config.subscription.SubscriptionRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSubscriptionsTest {
    private val url = "https://sub.example/s"
    private fun out(id: String, server: String, uuid: String = "u1") =
        StoredOutbound(id, "proxy", "vless", """{"type":"vless","tag":"proxy","server":"$server","server_port":443,"uuid":"$uuid"}""")
    private fun profile(id: String, name: String, server: String, group: String? = "g") =
        StoredProfile(id, name, "SUBSCRIPTION", listOf(out("o-$id", server)), "o-$id", groupId = group, subscriptionUrl = url, dnsPolicy = "TUNNEL")
    private fun draft(name: String, server: String, uuid: String = "u1"): ImportedProfileDraft {
        val o = NormalizedOutbound("n-$name", "proxy", "vless", """{"type":"vless","tag":"proxy","server":"$server","server_port":443,"uuid":"$uuid"}""")
        return ImportedProfileDraft(name, ProfileSource.SUBSCRIPTION, listOf(o), o.id, url)
    }

    @Test
    fun refreshKeepsSelectionAndFolderAndAddsIntoTheSameFolder() {
        val saved = StoredState(
            profiles = listOf(profile("a", "DE", "de.example"), profile("b", "NL", "nl.example"), profile("mine", "Мой", "my.example", group = null).copy(subscriptionUrl = null)),
            groups = listOf(StoredGroup("g", "Подписка")),
            selectedProfileId = "a",
            defaultDnsPolicy = "UNDERLAY",
        )
        val configs = DesktopSubscriptions.configs(saved, url)
        val origins = configs.associate { it.id to app.lernet.config.subscription.SubscriptionOrigin(it.name, SubscriptionRefresh.hashOf(it.outbounds)) }
        val plan = SubscriptionRefresh.plan(configs, listOf(draft("DE", "de.example", "u2"), draft("FI", "fi.example")), origins)
        val (next, added) = DesktopSubscriptions.apply(saved, url, plan)
        assertEquals("a", next.selectedProfileId)
        val de = next.profiles.first { it.id == "a" }
        assertEquals("o-a", de.selectedOutboundId)
        assertTrue(de.outbounds.single().singBoxJson.contains("u2"))
        assertEquals(listOf("a", "mine") + added, next.profiles.map { it.id })
        val fi = next.profiles.first { it.id == added.single() }
        assertEquals("g", fi.groupId)
        assertEquals(url, fi.subscriptionUrl)
        assertEquals("UNDERLAY", fi.dnsPolicy)
        assertEquals(url, next.groups.single().subscriptionUrl)
    }

    @Test
    fun goneConfigIsRemovedEvenWhenItHasRules() {
        val saved = StoredState(
            profiles = listOf(profile("a", "DE", "de.example"), profile("b", "NL", "nl.example")),
            groups = listOf(StoredGroup("g", "Подписка", subscriptionUrl = url)),
            rules = listOf(StoredRule("r", "b", sortIndex = 0)),
        )
        val configs = DesktopSubscriptions.configs(saved, url)
        val plan = SubscriptionRefresh.plan(configs, listOf(draft("DE", "de.example")), emptyMap())
        val (next, _) = DesktopSubscriptions.apply(saved, url, plan)
        assertEquals(listOf("a"), next.profiles.map { it.id })
        assertEquals(listOf("a"), next.profiles.filter { it.groupId == "g" }.map { it.id })
    }

    @Test
    fun ungroupedSubscriptionBecomesItsOwnFolder() {
        val saved = StoredState(profiles = listOf(profile("a", "DE", "de.example", group = null)))
        val next = DesktopSubscriptions.adoptAll(saved)
        val folder = next.groups.single()
        assertEquals(url, folder.subscriptionUrl)
        assertEquals("Подписка · sub.example", folder.name)
        assertEquals(folder.id, next.profiles.single().groupId)
    }

    @Test
    fun duplicateIsTheUsersOwnConfig() {
        val dir = java.nio.file.Files.createTempDirectory("lernet-dup")
        val store = DesktopStore(dir)
        store.save(StoredState(profiles = listOf(profile("a", "DE", "de.example", group = null))))
        DesktopController(store).use { controller ->
            controller.duplicateProfile("a")
            val copy = controller.state.value.saved.profiles.first { it.id != "a" }
            assertNull(copy.subscriptionUrl)
            assertNull(copy.groupId)
        }
    }
}
