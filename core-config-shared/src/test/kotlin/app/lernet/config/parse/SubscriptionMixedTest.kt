package app.lernet.config.parse

import app.lernet.config.net.RemoteTextFetcher
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class SubscriptionMixedTest {
    private val vless = "vless://11111111-1111-1111-1111-111111111111@v.example.com:443?security=tls#V"
    private val trojan = "trojan://pw@t.example.com:443#T"
    private val hy2 = "hy2://pw@h.example.com:443#H"

    @Test fun mixedSubscriptionKeepsEveryProtocol() {
        val body = Base64.getEncoder().encodeToString(listOf(vless, trojan, hy2).joinToString("\n").toByteArray())
        val result = SubscriptionParser.parse(body, "https://sub.example/x") as ImportResult.Success
        assertThat(result.drafts.map { it.outbounds.single().type }).containsExactly("vless", "trojan", "hysteria2").inOrder()
        assertThat(result.drafts.map { it.subscriptionUrl }.toSet()).containsExactly("https://sub.example/x")
        assertThat(result.skipped).isEmpty()
    }

    @Test fun brokenLinesAreReportedNotDropped() {
        val body = listOf(vless, "wireguard://k@w:51820", "trojan://pw@t.example.com:99999", "just text").joinToString("\n")
        val result = SubscriptionParser.parse(body, "https://sub.example/x") as ImportResult.Success
        assertThat(result.drafts).hasSize(1)
        assertThat(result.skipped.map { it.number }).containsExactly(2, 3, 4).inOrder()
        assertThat(result.skipped[0].reason).contains("wireguard://")
        val text = ImportReport.skipped(result.skipped)!!
        assertThat(text).startsWith("Не удалось добавить 3 ссылки: строка 2")
    }

    @Test fun nothingUsableFailsWithReasons() {
        val result = SubscriptionParser.parse("wireguard://k@w:1\nfoo", "u") as ImportResult.Failure
        assertThat(result.errors.single().message).contains("Не удалось добавить 2 ссылки")
    }

    @Test fun plurals() {
        assertThat(ImportReport.links(1)).isEqualTo("ссылку")
        assertThat(ImportReport.links(3)).isEqualTo("ссылки")
        assertThat(ImportReport.links(5)).isEqualTo("ссылок")
        assertThat(ImportReport.links(11)).isEqualTo("ссылок")
        assertThat(ImportReport.links(21)).isEqualTo("ссылку")
    }

    @Test fun updateIntervalHeaderReachesTheResult() {
        val coordinator = ImportCoordinator {
            RemoteTextFetcher.Result.Ok(trojan, "text/plain", mapOf("profile-update-interval" to "6"))
        }
        val result = coordinator.importSubscription("https://sub.example/x") as ImportResult.Success
        assertThat(result.updateIntervalHours).isEqualTo(6)
    }

    @Test fun pastedDocumentIsNotARefreshableSubscription() {
        val result = SubscriptionParser.parse("$trojan\n$hy2", subscriptionUrl = "") as ImportResult.Success
        assertThat(result.drafts.map { it.subscriptionUrl }.toSet()).containsExactly(null)
    }
}
