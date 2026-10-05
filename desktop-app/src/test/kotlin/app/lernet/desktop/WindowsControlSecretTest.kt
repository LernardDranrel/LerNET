package app.lernet.desktop

import com.sun.jna.Native
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Descriptor/layout checks only; this suite never creates files or calls Windows security APIs. */
class WindowsControlSecretTest {
    @Test
    fun `canonical Windows directory and file descriptors restrict control to system and administrators`() {
        assertTrue(ControlSecretPolicy.isProtectedDescriptor("O:BAD:P(A;OICI;FA;;;BA)(A;OICI;FA;;;SY)", directory = true))
        assertTrue(ControlSecretPolicy.isProtectedDescriptor("O:BAG:BAD:P(A;;0x1f01ff;;;SY)(A;;0x1f01ff;;;BA)", directory = false))
        assertTrue(ControlSecretPolicy.isProtectedDescriptor(
            "O:S-1-5-32-544D:P(A;;FA;;;S-1-5-18)(A;;FA;;;S-1-5-32-544)", directory = false,
        ))
    }

    @Test
    fun `individual owner cannot retain implicit control rights despite restricted dacl`() {
        assertFalse(ControlSecretPolicy.isProtectedDescriptor(
            "O:S-1-5-21-111-222-333-1001D:P(A;;FA;;;SY)(A;;FA;;;BA)", directory = false,
        ))
        assertFalse(ControlSecretPolicy.isProtectedDescriptor("O:SYD:P(A;;FA;;;SY)(A;;FA;;;BA)", directory = false))
    }

    @Test
    fun `user read permissions and duplicate privileged entries are rejected`() {
        listOf(
            "O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;BU)",
            "O:BAD:P(A;;FA;;;SY)(A;;FR;;;BU)",
            "O:BAD:P(A;;FA;;;BA)(A;;FA;;;BA)",
            "O:BAD:P(A;;FA;;;SY)(A;;FA;;;CO)",
            "O:BAD:P(A;;FA;;;SY)(A;;FA;;;AU)",
        ).forEach { assertFalse(ControlSecretPolicy.isProtectedDescriptor(it, directory = false)) }
    }

    @Test
    fun `inheritance and partial access cannot be mistaken for complete protected ownership`() {
        listOf(
            "O:BAD:(A;;FA;;;SY)(A;;FA;;;BA)",
            "O:BAD:P(A;ID;FA;;;SY)(A;ID;FA;;;BA)",
            "O:BAD:P(A;;FR;;;SY)(A;;FA;;;BA)",
            "O:BAD:P(D;;FA;;;SY)(A;;FA;;;BA)",
            "untrustedO:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)",
        ).forEach { assertFalse(ControlSecretPolicy.isProtectedDescriptor(it, directory = false)) }
        assertFalse(ControlSecretPolicy.isProtectedDescriptor("O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)", directory = true))
        assertFalse(ControlSecretPolicy.isProtectedDescriptor("O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)", directory = false))
    }

    @Test
    fun `generated native security attributes have the documented pointer aligned layout`() {
        val attributes = ControlSecurityAttributes()
        assertEquals(if (Native.POINTER_SIZE == 8) 24 else 12, attributes.size())
        assertEquals(attributes.size(), attributes.length)
        assertEquals(0, attributes.inheritHandle)
        assertTrue(ControlSecretPolicy.isProtectedDescriptor(ControlSecretPolicy.descriptor(true), true))
        assertTrue(ControlSecretPolicy.isProtectedDescriptor(ControlSecretPolicy.descriptor(false), false))
    }
}
