package app.lernet.subscription

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import app.lernet.config.parse.ImportedProfileDraft
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.subscription.SubscriptionOrigin
import app.lernet.config.subscription.SubscriptionRefresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubscriptionRepositoryTest {
    private val url = "https://sub.example/s"
    private fun draft(name: String, server: String, uuid: String = "u1"): ImportedProfileDraft {
        val o = NormalizedOutbound("n-$name-$uuid", "proxy", "vless", """{"type":"vless","tag":"proxy","server":"$server","server_port":443,"uuid":"$uuid"}""")
        return ImportedProfileDraft(name, ProfileSource.SUBSCRIPTION, listOf(o), o.id, url)
    }

    @Test
    fun refreshUpdatesInPlaceAddsToFolderAndRemovesGone(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repo = ConfigRepository(db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(), db.groupMemberDao(), database = db)
            val ids = repo.importDrafts(listOf(draft("DE", "de.example"), draft("NL", "nl.example"), draft("SE", "se.example")))
            val folder = repo.upsertGroup("Подписка")
            repo.setGroupMembers(folder, ids)
            repo.renameProfile(ids[1], "Мой NL")
            val before = repo.subscribedConfigs(url, activeProfileId = ids[0])
            val origins = mapOf(
                ids[0] to SubscriptionOrigin("DE", SubscriptionRefresh.hashOf(before.first { it.id == ids[0] }.outbounds)),
                ids[1] to SubscriptionOrigin("NL", SubscriptionRefresh.hashOf(before.first { it.id == ids[1] }.outbounds)),
                ids[2] to SubscriptionOrigin("SE", SubscriptionRefresh.hashOf(before.first { it.id == ids[2] }.outbounds)),
            )
            val plan = SubscriptionRefresh.plan(before, listOf(draft("DE", "de.example", "u2"), draft("NL", "nl.example", "u3"), draft("FI", "fi.example")), origins)
            val added = repo.applySubscriptionPlan(url, plan, DnsPolicy.UNDERLAY)

            val de = repo.getProfile(ids[0])!!
            assertThat(de.selectedOutboundId).isEqualTo(before.first { it.id == ids[0] }.selectedOutboundId)
            assertThat(before.first { it.id == ids[0] }.selected).isTrue()
            assertThat(de.outbounds.single().singBoxJson).contains("u2")
            assertThat(repo.getProfile(ids[1])!!.name).isEqualTo("Мой NL")
            assertThat(repo.getProfile(ids[2])).isNull()
            val fi = repo.getProfile(added.single())!!
            assertThat(fi.subscriptionUrl).isEqualTo(url)
            assertThat(fi.dnsPolicy).isEqualTo(DnsPolicy.UNDERLAY)
            assertThat(repo.groups.first().single().profileIds).containsExactly(ids[0], ids[1], added.single()).inOrder()
        } finally {
            db.close()
        }
    }

    @Test
    fun duplicateLeavesTheSubscription(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repo = ConfigRepository(db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(), db.groupMemberDao(), database = db)
            val id = repo.importDrafts(listOf(draft("DE", "de.example"))).single()
            val copy = repo.duplicateProfile(id)!!
            assertThat(repo.getProfile(copy)!!.subscriptionUrl).isNull()
            assertThat(repo.subscriptionUrls()).containsExactly(url)
        } finally {
            db.close()
        }
    }
}
