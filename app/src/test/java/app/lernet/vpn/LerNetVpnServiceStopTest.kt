package app.lernet.vpn

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.ConnectionController
import app.lernet.engine.UnavailableBoxEngine
import app.lernet.vpn.expert.ExpertServiceRestorer
import app.lernet.vpn.expert.ExpertVpnSession
import app.lernet.vpn.expert.ExpertVpnToken
import app.lernet.vpn.expert.RetainedTunDescriptor
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowParcelFileDescriptor
import org.robolectric.shadows.ShadowVpnService

/**
 * JVM regressions use actual regular-file handles with synthetic Android FD numbers.
 * They verify service ownership and handle closure; real TUN close proof requires the Android guest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [NumericFdShadow::class])
class LerNetVpnServiceStopTest {
    private val reservations = mutableListOf<ExpertVpnToken>()
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private val files = mutableListOf<java.io.File>()
    private val databases = mutableListOf<LerNetDatabase>()
    private val controllerScopes = mutableListOf<CoroutineScope>()

    @After
    fun releaseTestResources() {
        descriptors.forEach { runCatching { it.close() } }
        reservations.forEach(ExpertVpnSession::release)
        VpnRuntime.current()?.let(VpnRuntime::detach)
        LibboxPlatformRegistry.peek()?.let(LibboxPlatformRegistry::unregister)
        ExpertServiceRestorer.register({ false }, {}, {})
        controllerScopes.forEach { it.cancel() }
        databases.forEach { it.close() }
        files.forEach { it.delete() }
    }

    @Test
    fun explicitExpertStopClosesTheOriginalWithoutWaitingForDestroy() {
        val failures = mutableListOf<String>()
        val token = reserve(failures)
        val service = newService()
        ExpertVpnSession.attachService(instance(service))
        val original = originalDescriptor()
        val originalHandle = original.fileDescriptor
        service.retainTun(original)
        VpnRuntime.attach(service)

        service.stopExpert(token)

        assertThat(originalHandle.valid()).isFalse()
        assertThat(VpnRuntime.current()).isNull()
        assertThat(ExpertVpnSession.identity).isNull()
        assertThat(ExpertVpnSession.isOwned).isTrue()
        assertThat(ExpertVpnSession.serviceCloseConfirmed(token)).isTrue()
        assertThat(failures).isEmpty()
        service.onDestroy()
        assertThat(failures).isEmpty()
    }

    @Test
    fun failedOriginalCloseRetainsTheServiceAndLeaseAcrossRetryAndDestroy() {
        val failures = mutableListOf<String>()
        val token = reserve(failures)
        val service = newService()
        ExpertVpnSession.attachService(instance(service))
        var closeCalls = 0
        val owner = RetainedTunDescriptor<ParcelFileDescriptor> {
            closeCalls++
            it.close()
            throw IOException("close completion unavailable")
        }
        field("tunDescriptor").set(service, owner)
        val original = originalDescriptor()
        val originalHandle = original.fileDescriptor
        service.retainTun(original)
        val identity = ExpertVpnSession.identity
        VpnRuntime.attach(service)

        assertThrows(IOException::class.java) { service.stopExpert(token) }
        assertThrows(IllegalStateException::class.java) { service.stopExpert(token) }
        val revokeThread = Executors.newSingleThreadExecutor()
        try {
            revokeThread.submit { service.onRevoke() }.get(5, TimeUnit.SECONDS)
        } finally {
            revokeThread.shutdownNow()
        }
        service.onDestroy()

        assertThat(closeCalls).isEqualTo(1)
        assertThat(originalHandle.valid()).isFalse()
        assertThat(owner.descriptor).isSameInstanceAs(original)
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(ExpertVpnSession.identity).isEqualTo(identity)
        assertThat(ExpertVpnSession.serviceCloseConfirmed(token)).isFalse()
        assertThat(ExpertVpnSession.isOwned).isTrue()
        assertThrows(IllegalStateException::class.java) { service.beforeOpenTun() }
        assertThrows(IllegalStateException::class.java) { ExpertVpnSession.reserve { _, _ -> } }
    }

    @Test
    fun staleTokenAndWrongServiceCannotCloseTheCurrentDescriptor() {
        val failures = mutableListOf<String>()
        val first = reserve(failures)
        ExpertVpnSession.release(first)
        val current = reserve(failures)
        val service = newService()
        val otherService = newService()
        ExpertVpnSession.attachService(instance(service))
        val original = originalDescriptor()
        service.retainTun(original)
        val identity = ExpertVpnSession.identity
        VpnRuntime.attach(service)

        assertThrows(IllegalStateException::class.java) { service.stopExpert(first) }
        assertThrows(IllegalStateException::class.java) { otherService.stopExpert(current) }

        assertThat(original.fileDescriptor.valid()).isTrue()
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(ExpertVpnSession.identity).isEqualTo(identity)
        assertThat(failures).isEmpty()
        service.stopExpert(current)
    }

    @Test
    fun delayedSimpleHardStopCannotCloseTheReservedExpertTun() {
        val failures = mutableListOf<String>()
        val token = reserve(failures)
        val service = newService()
        ExpertVpnSession.attachService(instance(service))
        val original = originalDescriptor()
        service.retainTun(original)
        val identity = ExpertVpnSession.identity
        VpnRuntime.attach(service)

        service.onStartCommand(Intent().setAction(AndroidEngineProcessHost.ACTION_HARD_STOP), 0, 4)

        assertThat(original.fileDescriptor.valid()).isTrue()
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(ExpertVpnSession.identity).isEqualTo(identity)
        assertThat(failures).isEmpty()
        service.stopExpert(token)
    }

    @Test
    fun cancelledExpertStartCannotDetachAnUnreservedSimpleDescriptor() {
        val failures = mutableListOf<String>()
        val cancelled = reserve(failures)
        ExpertVpnSession.release(cancelled)
        val service = newService()
        val original = originalDescriptor()
        service.retainTun(original)
        VpnRuntime.attach(service)

        service.onStartCommand(
            Intent().setAction(ExpertVpnSession.ACTION_START)
                .putExtra(ExpertVpnSession.EXTRA_GENERATION, cancelled.generation),
            0, 4,
        )

        assertThat(original.fileDescriptor.valid()).isTrue()
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(ExpertVpnSession.isOwned).isFalse()
        service.onDestroy()
    }

    @Test
    fun lateRevokeFromClosedExpertCannotAbortANewerSimpleService() {
        val failures = mutableListOf<String>()
        val token = reserve(failures)
        val old = newService()
        ExpertVpnSession.attachService(instance(old))
        old.retainTun(originalDescriptor())
        VpnRuntime.attach(old)
        old.stopExpert(token)
        ExpertVpnSession.release(token)
        val current = newService()
        val original = originalDescriptor()
        current.retainTun(original)
        VpnRuntime.attach(current)
        var revokeCalls = 0
        ExpertServiceRestorer.register({ false }, {}, { revokeCalls++ })

        old.onRevoke()
        // A protect failure can already have captured the old VpnRuntime revoke sink.
        LerNetVpnService::class.java.getDeclaredMethod("notifyRevoked").apply { isAccessible = true }.invoke(old)

        assertThat(revokeCalls).isEqualTo(0)
        assertThat(original.fileDescriptor.valid()).isTrue()
        assertThat(VpnRuntime.current()).isSameInstanceAs(current)
        assertThat(failures).isEmpty()
        current.onDestroy()
    }

    @Test
    @Config(shadows = [StartIdRetirementShadow::class, NumericFdShadow::class])
    fun cancelledEmptyBootstrapDetachesBeforeDestroyAndCanAcceptTheNextSimpleStart() {
        val cancelled = reserve(mutableListOf())
        ExpertVpnSession.release(cancelled)
        val service = newService()
        val bootstrap = publishBootstrap(service)
        val retirement = Shadow.extract<StartIdRetirementShadow>(service)
        retirement.latestRegisteredStartId = 4
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(LibboxPlatformRegistry.peek()).isSameInstanceAs(bootstrap)

        service.onStartCommand(cancelledStart(cancelled), 0, 4)

        // A system binding can keep this object alive. Physical/registry cleanup must
        // already be confirmed here; onDestroy deliberately has not been delivered.
        assertThat(VpnRuntime.current()).isNull()
        assertThat(LibboxPlatformRegistry.peek()).isNull()
        assertThat(retirement.retirementRequests).containsExactly(4)
        assertThat(retirement.scopedStops).containsExactly(4)
        assertThat(retirement.unscopedStops).isEqualTo(0)
        assertThrows(IllegalStateException::class.java) { service.beforeOpenTun() }

        prepareSimpleDependencies(service)
        retirement.latestRegisteredStartId = 5
        service.onStartCommand(Intent().setAction(AndroidEngineProcessHost.ACTION_SIMPLE_START), 0, 5)
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(LibboxPlatformRegistry.peek()).isNotNull()
        assertThat(LibboxPlatformRegistry.peek()).isNotSameInstanceAs(bootstrap)
        service.beforeOpenTun()
        val original = originalDescriptor()
        service.retainTun(original)
        assertThat(original.fileDescriptor.valid()).isTrue()
        service.onDestroy()
    }

    @Test
    @Config(shadows = [StartIdRetirementShadow::class, NumericFdShadow::class])
    fun cancelledBootstrapCannotRetireANewerRegisteredSimpleStart() {
        val cancelled = reserve(mutableListOf())
        ExpertVpnSession.release(cancelled)
        val service = newService()
        val bootstrap = publishBootstrap(service)
        val retirement = Shadow.extract<StartIdRetirementShadow>(service)
        // Android has registered Simple's newer start, but its callback remains queued.
        retirement.latestRegisteredStartId = 5

        service.onStartCommand(cancelledStart(cancelled), 0, 4)

        assertThat(retirement.retirementRequests).containsExactly(4)
        assertThat(retirement.scopedStops).isEmpty()
        assertThat(retirement.unscopedStops).isEqualTo(0)
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(LibboxPlatformRegistry.peek()).isSameInstanceAs(bootstrap)

        prepareSimpleDependencies(service)
        service.onStartCommand(Intent().setAction(AndroidEngineProcessHost.ACTION_SIMPLE_START), 0, 5)
        assertThat(VpnRuntime.current()).isSameInstanceAs(service)
        assertThat(LibboxPlatformRegistry.peek()).isSameInstanceAs(bootstrap)
        service.beforeOpenTun()
        val original = originalDescriptor()
        service.retainTun(original)
        assertThat(original.fileDescriptor.valid()).isTrue()
        service.onDestroy()
    }

    private fun reserve(failures: MutableList<String>): ExpertVpnToken =
        ExpertVpnSession.reserve { _, reason -> failures += reason }.also(reservations::add)

    // Attach only the Android base context; onCreate's Hilt/native bootstrap is unnecessary.
    private fun newService(): LerNetVpnService = Robolectric.buildService(LerNetVpnService::class.java).get()

    private fun instance(service: LerNetVpnService): String = field("serviceInstance").get(service) as String

    private fun field(name: String) = LerNetVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun cancelledStart(token: ExpertVpnToken): Intent =
        Intent().setAction(ExpertVpnSession.ACTION_START)
            .putExtra(ExpertVpnSession.EXTRA_GENERATION, token.generation)

    private fun publishBootstrap(service: LerNetVpnService): LibboxPlatform =
        LibboxPlatform(service.applicationContext, vpn = service, tunRequired = true).also {
            field("platform").set(service, it)
            LibboxPlatformRegistry.register(it)
            VpnRuntime.attach(service)
        }

    private fun prepareSimpleDependencies(service: LerNetVpnService) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java,
        ).build().also(databases::add)
        service.repository = ConfigRepository(
            database.profileDao(), database.outboundDao(), database.ruleNodeDao(),
            database.groupDao(), database.groupMemberDao(), database = database,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(controllerScopes::add)
        service.controller = ConnectionController(UnavailableBoxEngine(), controllerScope)
    }

    private fun originalDescriptor(): ParcelFileDescriptor {
        val file = Files.createTempFile("lernet-service-tun", ".test").toFile().also(files::add)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).also {
            assertThat(it.fileDescriptor.valid()).isTrue()
            descriptors += it
        }
    }
}

/** Models Android's registered startId, including a newer callback still waiting on Main. */
@Implements(VpnService::class)
class StartIdRetirementShadow : ShadowVpnService() {
    var latestRegisteredStartId = 0
    val retirementRequests = mutableListOf<Int>()
    val scopedStops = mutableListOf<Int>()
    var unscopedStops = 0

    @Implementation
    override fun stopSelfResult(id: Int): Boolean {
        retirementRequests += id
        return id == latestRegisteredStartId && super.stopSelfResult(id)
    }

    @Implementation
    override fun stopSelf(id: Int) {
        scopedStops += id
        super.stopSelf(id)
    }

    @Implementation
    override fun stopSelf() {
        unscopedStops++
        super.stopSelf()
    }
}

/**
 * Windows JVM file handles are valid while FileDescriptor.fd remains -1. Model only the Android
 * numeric identity; inherited open/close still own the real JVM file and no fd/handle is rewritten.
 */
@Implements(ParcelFileDescriptor::class)
class NumericFdShadow : ShadowParcelFileDescriptor() {
    private val numericIdentity = nextIdentity.getAndIncrement()

    @Implementation
    override fun getFd(): Int {
        super.getFd() // Preserve the inherited closed-descriptor check.
        return numericIdentity
    }

    companion object {
        private val nextIdentity = AtomicInteger(1_000)
    }
}
