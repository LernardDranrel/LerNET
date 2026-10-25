package app.lernet.config.subscription

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubscriptionFoldersTest {
    private val url = "https://sub.example/secret"

    @Test fun folderNameUsesHostOnly() {
        assertThat(SubscriptionFolders.folderName(url)).isEqualTo("Подписка · sub.example")
        assertThat(SubscriptionFolders.folderName("not a url")).isEqualTo("Подписка")
        assertThat(SubscriptionFolders.owned(null)).isFalse()
        assertThat(SubscriptionFolders.owned(" https://x ")).isTrue()
    }

    @Test fun markedFolderKeepsItsNameAndGainsNewMembers() {
        val folders = listOf(FolderView("g", "Моя подписка", listOf("a", "b"), url))
        val plan = SubscriptionFolders.adopt(url, listOf("a", "c"), folders)
        assertThat(plan.create).isFalse()
        assertThat(plan.folderId).isEqualTo("g")
        assertThat(plan.name).isEqualTo("Моя подписка")
        assertThat(plan.memberIds).containsExactly("a", "c").inOrder()
    }

    @Test fun oneShotFolderThatHoldsOnlyTheSubscriptionIsAdopted() {
        val folders = listOf(
            FolderView("mine", "Свои", listOf("user")),
            FolderView("old", "Серверы", listOf("a", "b")),
        )
        val plan = SubscriptionFolders.adopt(url, listOf("b", "a", "c"), folders)
        assertThat(plan.create).isFalse()
        assertThat(plan.folderId).isEqualTo("old")
        assertThat(plan.memberIds).containsExactly("a", "b", "c").inOrder()
    }

    @Test fun mixedFolderStaysTheUsersAndANewSubscriptionFolderIsCreated() {
        val folders = listOf(FolderView("mix", "Всё", listOf("a", "user")))
        val plan = SubscriptionFolders.adopt(url, listOf("a"), folders)
        assertThat(plan.create).isTrue()
        assertThat(plan.folderId).isNull()
        assertThat(plan.name).isEqualTo("Подписка · sub.example")
        assertThat(plan.memberIds).containsExactly("a")
    }

    @Test fun acceptedMembersLocksSubscriptionFolderAndRejectsOwnedConfigs() {
        assertThat(
            SubscriptionFolders.acceptedMembers(url, listOf("a", "b"), listOf("a", "c")) { it == "a" },
        ).containsExactly("a", "b").inOrder()
        assertThat(
            SubscriptionFolders.acceptedMembers(null, listOf("a"), listOf("a", "owned", "mine")) { it == "owned" },
        ).containsExactly("a", "mine").inOrder()
    }
}
