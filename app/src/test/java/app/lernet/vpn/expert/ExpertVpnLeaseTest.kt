package app.lernet.vpn.expert

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpertVpnLeaseTest {
    @Test
    fun reservationPreventsParallelSimpleAndExpertOwners() {
        val lease = ExpertVpnLease()
        lease.reserve()
        assertThat(lease.isOwned).isTrue()
        assertThrows(IllegalStateException::class.java) { lease.reserve() }
    }

    @Test
    fun staleCleanupCannotReleaseANewerSession() {
        val lease = ExpertVpnLease()
        val first = lease.reserve()
        lease.release(first)
        val second = lease.reserve()
        assertThat(lease.release(first)).isFalse()
        assertThat(lease.isOwned).isTrue()
        assertThat(lease.release(second)).isTrue()
    }

    @Test
    fun livePolicyCannotReplaceTheTunDescriptor() {
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        lease.requireFirstTun("service-A")
        val identity = lease.established("service-A", 27)
        assertThat(identity!!.instanceId).contains("service-A:27")
        assertThrows(IllegalStateException::class.java) { lease.requireFirstTun("service-A") }
        assertThat(lease.identity).isEqualTo(identity)
    }

    @Test
    fun recreatedServiceCannotBorrowActiveSession() {
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        assertThrows(IllegalStateException::class.java) { lease.attach(token, "service-B") }
    }

    @Test
    fun releasedServiceCannotEstablishTunForStaleOwner() {
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        lease.release(token)
        assertThat(lease.established("service-A", 27)).isNull()
        assertThat(lease.identity).isNull()
    }

    @Test
    fun destroyedServiceInvalidatesTunWithoutReleasingItsReservation() {
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        lease.established("service-A", 27)
        lease.serviceDetached("service-A")
        assertThat(lease.identity).isNull()
        assertThat(lease.isOwned).isTrue()
        assertThrows(IllegalStateException::class.java) { lease.attach(token, "service-B") }
        assertThrows(IllegalStateException::class.java) { lease.requireFirstTun("service-A") }
        assertThat(lease.release(token)).isTrue()
    }

    @Test
    fun staleStopCannotCloseANewerTunEvenWhenAndroidReusesTheServiceInstance() {
        val lease = ExpertVpnLease()
        val first = lease.reserve()
        lease.attach(first, "service-A")
        lease.closeService(first, "service-A") {}
        lease.release(first)
        val second = lease.reserve()
        lease.attach(second, "service-A")
        val current = lease.established("service-A", 31)
        var closeCalls = 0

        assertThrows(IllegalStateException::class.java) {
            lease.closeService(first, "service-A") { closeCalls++ }
        }

        assertThat(closeCalls).isEqualTo(0)
        assertThat(lease.identity).isEqualTo(current)
        assertThat(lease.ownsService(second, "service-A")).isTrue()
    }

    @Test
    fun wrongServiceCannotCloseTheReservedTun() {
        val lease = ExpertVpnLease()
        val token = lease.reserve()
        lease.attach(token, "service-A")
        val current = lease.established("service-A", 31)
        var closeCalls = 0

        assertThrows(IllegalStateException::class.java) {
            lease.closeService(token, "service-B") { closeCalls++ }
        }

        assertThat(closeCalls).isEqualTo(0)
        assertThat(lease.identity).isEqualTo(current)
        assertThat(lease.serviceCloseConfirmed(token)).isFalse()
    }

    @Test
    fun cancelledPendingStartCannotBorrowTheNextReservation() {
        val lease = ExpertVpnLease()
        val cancelled = lease.reserve()
        assertThat(lease.serviceCloseConfirmed(cancelled)).isTrue()
        lease.release(cancelled)
        val next = lease.reserve()

        assertThat(lease.acceptsServiceStart(cancelled, "service-A")).isFalse()
        assertThat(lease.acceptsServiceStart(next, "service-A")).isTrue()
        lease.attach(next, "service-A")
        lease.closeService(next, "service-A") {}
        assertThat(lease.acceptsServiceStart(next, "service-A")).isFalse()
        assertThrows(IllegalStateException::class.java) { lease.attach(next, "service-A") }
    }
}
