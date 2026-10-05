package app.lernet.vpn.expert

import com.google.common.truth.Truth.assertThat
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Test

class RetainedTunDescriptorTest {
    @Test
    fun throwingCloseKeepsOwnershipEvenIfTheDescriptorWasConsumedBeforeTheError() {
        val file = Files.createTempFile("lernet-tun-close", ".test").toFile()
        val descriptor = FileInputStream(file)
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        val identity = lease.established("service-A", 31)
        var closeCalls = 0
        val owner = RetainedTunDescriptor<FileInputStream> {
            closeCalls++
            it.close()
            throw IOException("close completion unavailable")
        }
        owner.retain(descriptor)
        try {
            assertThrows(IOException::class.java) { lease.closeService(token, "service-A", owner::close) }
            assertThat(descriptor.fd.valid()).isFalse()
            assertThrows(IllegalStateException::class.java) { lease.closeService(token, "service-A", owner::close) }

            assertThat(closeCalls).isEqualTo(1)
            assertThat(owner.descriptor).isSameInstanceAs(descriptor)
            assertThat(lease.identity).isEqualTo(identity)
            assertThat(lease.isOwned).isTrue()
            assertThat(lease.serviceCloseConfirmed(token)).isFalse()
            assertThrows(IllegalStateException::class.java) { lease.requireFirstTun("service-A") }
            assertThrows(IllegalStateException::class.java) { lease.reserve() }
        } finally {
            descriptor.close()
            file.delete()
        }
    }

    @Test
    fun successfulCloseDetachesTheOriginalButKeepsTheNativeReservation() {
        val file = Files.createTempFile("lernet-tun-close", ".test").toFile()
        val descriptor = FileInputStream(file)
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        lease.established("service-A", 31)
        val owner = RetainedTunDescriptor<FileInputStream>(FileInputStream::close)
        owner.retain(descriptor)
        try {
            lease.closeService(token, "service-A", owner::close)

            assertThat(descriptor.fd.valid()).isFalse()
            assertThat(owner.descriptor).isNull()
            assertThat(lease.identity).isNull()
            assertThat(lease.isOwned).isTrue()
            assertThat(lease.serviceCloseConfirmed(token)).isTrue()
            assertThrows(IllegalStateException::class.java) { lease.reserve() }
            assertThat(lease.release(token)).isTrue()
        } finally {
            descriptor.close()
            file.delete()
        }
    }
}
