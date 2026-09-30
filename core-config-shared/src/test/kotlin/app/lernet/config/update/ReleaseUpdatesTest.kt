package app.lernet.config.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private val hash = "a".repeat(64)
    private fun payload(version: String = "1.0.12", extra: String = "", assetUrl: String =
        "${ReleaseUpdates.RELEASES}/download/v$version/LerNET-$version-install.exe", digest: String = "sha256:$hash") = """
        {"draft":false,"prerelease":false,"tag_name":"v$version",
         "html_url":"${ReleaseUpdates.RELEASES}/tag/v$version","body":"Release notes",
         "assets":[{"name":"LerNET-$version-install.exe","state":"uploaded","size":100,
         "digest":"$digest","browser_download_url":"$assetUrl"}]$extra}
    """.trimIndent()

    @Test fun comparesVersionsNumerically() {
        assertTrue(ReleaseUpdates.isNewer("1.0.12", "1.0.9"))
        assertTrue(ReleaseUpdates.isNewer("1.1.0", "1.0.99"))
        assertFalse(ReleaseUpdates.isNewer("1.0.12", "1.0.12"))
        assertFalse(ReleaseUpdates.isNewer("1.0.12-beta", "1.0.11"))
        assertFalse(ReleaseUpdates.isNewer("999999999999.0.0", "1.0.11"))
    }
    @Test fun acceptsStableReleaseAndVerifiedInstaller() {
        val release = ReleaseUpdates.parse(payload())
        assertEquals(hash, release.windowsInstaller()!!.sha256)
        assertEquals("1.0.12", release.version)
    }
    @Test fun excludesMissingDigestOrForeignAsset() {
        assertNull(ReleaseUpdates.parse(payload(digest = "unknown")).windowsInstaller())
        assertNull(ReleaseUpdates.parse(payload(assetUrl = "https://example.com/fake.exe")).windowsInstaller())
    }
    @Test fun rejectsDraftPrereleaseAndUnexpectedRepository() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseUpdates.parse(payload().replace("\"draft\":false", "\"draft\":true")) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseUpdates.parse(payload().replace("\"prerelease\":false", "\"prerelease\":true")) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseUpdates.parse(payload().replace("/tag/v", "/tag/wrong-v")) }
    }
    @Test fun limitsResponseSize() {
        assertThrows(IllegalStateException::class.java) { ReleaseUpdates.readBounded(ByteArray(11).inputStream(), 10) }
        assertEquals(10, ReleaseUpdates.readBounded(ByteArray(10).inputStream(), 10).size)
    }
    @Test fun autoCheckIsDailyManualCheckOverridesAndDisabledMakesNoRequest() = runBlocking {
        var automatic = true; var stamp = 0L; var calls = 0
        val monitor = UpdateMonitor("1.0.11", { automatic }, { automatic = it }, { stamp }, { stamp = it },
            { calls++; ReleaseUpdates.parse(payload()) }, { 100_000_000L })
        monitor.check(); monitor.check()
        assertEquals(1, calls)
        assertEquals("1.0.12", monitor.state.value.release!!.version)
        monitor.check(manual = true)
        assertEquals(2, calls)
        monitor.automatic(false); monitor.check()
        assertEquals(2, calls)
    }
    @Test fun failedChecksAlsoThrottleAndNeverBreakStartup() = runBlocking {
        var stamp = 0L; var calls = 0
        val monitor = UpdateMonitor("1.0.12", { true }, {}, { stamp }, { stamp = it },
            { calls++; error("unreachable") }, { 100_000_000L })
        monitor.check(); monitor.check()
        assertEquals(1, calls)
        assertFalse(monitor.state.value.checking)
        assertNull(monitor.state.value.release)
    }
}
