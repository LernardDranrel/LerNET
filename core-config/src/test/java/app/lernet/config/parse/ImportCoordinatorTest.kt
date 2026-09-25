package app.lernet.config.parse

import app.lernet.config.net.RemoteTextFetcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportCoordinatorTest {
    @Test
    fun jsonUrlUsesFetcher() {
        val fetcher = RemoteTextFetcher { url ->
            assertThat(url).isEqualTo("https://example.com/c.json")
            RemoteTextFetcher.Result.Ok("""{"type":"vless","tag":"n","server":"h","server_port":1,"uuid":"u"}""")
        }
        val result = ImportCoordinator(fetcher).importJsonUrl("https://example.com/c.json") as ImportResult.Success
        assertThat(result.drafts.single().outbounds.single().tag).isEqualTo("n")
    }

    @Test
    fun rejectsNonHttpUrl() {
        val result = ImportCoordinator { RemoteTextFetcher.Result.Ok("") }
            .importSubscription("ftp://x") as ImportResult.Failure
        assertThat(result.errors.single().field).isEqualTo("subscriptionUrl")
    }

    @Test
    fun surfacesFetchError() {
        val result = ImportCoordinator { RemoteTextFetcher.Result.Err("HTTP 404") }
            .importJsonUrl("https://example.com/missing") as ImportResult.Failure
        assertThat(result.errors.single().message).contains("404")
    }

    @Test
    fun overridesSingleDraftDisplayName() {
        val coordinator = ImportCoordinator { RemoteTextFetcher.Result.Ok("") }
        val parsed = coordinator.importVless("vless://11111111-1111-1111-1111-111111111111@example.com:443#Node")
        val renamed = coordinator.withDisplayName(parsed, "  Дом  ") as ImportResult.Success
        assertThat(renamed.drafts.single().name).isEqualTo("Дом")
        val ignored = coordinator.withDisplayName(parsed, "  ") as ImportResult.Success
        assertThat(ignored.drafts.single().name).isEqualTo("Node")
    }
}
