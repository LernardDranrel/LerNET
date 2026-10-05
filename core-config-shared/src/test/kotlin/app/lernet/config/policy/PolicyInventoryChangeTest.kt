package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PolicyInventoryChangeTest {
    private fun profile(id: String) = TransferProfile(
        id, id, "JSON_PASTE",
        listOf(
            TransferOutbound(
                "out-$id", "proxy", "socks",
                "{\"type\":\"socks\",\"server\":\"example.invalid\",\"server_port\":1080}"
            )
        ),
        "out-$id"
    )
    private val before = TransferBundle(
        scope = "all", profiles = listOf(profile("first")),
        groups = listOf(TransferGroup("folder", "Folder", listOf("first"))), rules = emptyList()
    )

    @Test
    fun aNewFolderCandidateIsAnAddition() {
        val after = before.copy(
            profiles = before.profiles + profile("next"),
            groups = listOf(before.groups.single().copy(profileIds = listOf("first", "next")))
        )
        assertThat(PolicyInventoryChange.isAddition(before, after)).isTrue()
    }

    @Test
    fun replacementCredentialsOrExistingOwnerRulesNeverPublishAsAnAddition() {
        val added = before.copy(profiles = before.profiles + profile("next"))
        val changed = added.copy(profiles = added.profiles.map { if (it.id == "first") it.copy(name = "changed") else it })
        assertThat(PolicyInventoryChange.isAddition(before, changed)).isFalse()
        val changedEndpoint = added.copy(
            profiles = added.profiles.map { profile ->
                if (profile.id == "first") {
                    profile.copy(
                        outbounds = profile.outbounds.map {
                            it.copy(singBoxJson = it.singBoxJson.replace("example.invalid", "other.invalid"))
                        }
                    )
                } else {
                    profile
                }
            }
        )
        assertThat(PolicyInventoryChange.isAddition(before, changedEndpoint)).isFalse()
        val changedRules = added.copy(rules = listOf(TransferRule("new-rule", "first", sortIndex = 0, action = "proxy")))
        assertThat(PolicyInventoryChange.isAddition(before, changedRules)).isFalse()
    }

    @Test
    fun deletingOrMerelyRenamingInventoryNeverPublishesAutomatically() {
        assertThat(PolicyInventoryChange.isAddition(before, before.copy(profiles = listOf(profile("next"))))).isFalse()
        val renamed = before.copy(groups = listOf(before.groups.single().copy(name = "renamed")))
        assertThat(PolicyInventoryChange.isAddition(before, renamed)).isFalse()
        assertThat(PolicyInventoryChange.isAddition(before, before)).isFalse()
    }

    @Test
    fun oldFolderOrderAndImplicitPreferredMustStayStableWhenCandidatesAreAdded() {
        val two = before.copy(
            profiles = listOf(profile("first"), profile("second")),
            groups = listOf(before.groups.single().copy(profileIds = listOf("first", "second")))
        )
        fun changed(order: List<String>) = two.copy(
            profiles = two.profiles + profile("next"),
            groups = listOf(two.groups.single().copy(profileIds = order))
        )
        assertThat(PolicyInventoryChange.isAddition(two, changed(listOf("first", "second", "next")))).isTrue()
        assertThat(PolicyInventoryChange.isAddition(two, changed(listOf("second", "first", "next")))).isFalse()
        assertThat(PolicyInventoryChange.isAddition(two, changed(listOf("next", "first", "second")))).isFalse()
        assertThat(PolicyInventoryChange.isAddition(two, changed(listOf("first", "next", "second")))).isFalse()
    }
}
