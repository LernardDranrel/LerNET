package app.lernet.desktop

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DesktopUpdatesTest {
    private val bytes = ByteArray(130_000) { (it % 255).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun verifiesEntireDownloadBeforeItCanBeLaunched() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        DesktopUpdates.copyVerified(bytes.inputStream(), output, bytes.size.toLong(), digest) { progress += it }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(100, progress.last())
    }
    @Test fun rejectsCorruptionTruncationAndOversizedDownload() {
        assertThrows(IllegalStateException::class.java) {
            DesktopUpdates.copyVerified(bytes.copyOf().apply { this[0] = 42 }.inputStream(), ByteArrayOutputStream(), bytes.size.toLong(), digest)
        }
        assertThrows(IllegalStateException::class.java) {
            DesktopUpdates.copyVerified(bytes.inputStream(), ByteArrayOutputStream(), bytes.size.toLong() + 1, digest)
        }
        assertThrows(IllegalStateException::class.java) {
            DesktopUpdates.copyVerified(bytes.inputStream(), ByteArrayOutputStream(), bytes.size.toLong() - 1, digest)
        }
    }
}
