package app.lernet.engine.policy

import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyExit
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.ProfileExitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpertRuntimeControllerTest {
    private val inventory = PolicyInventory(setOf("a", "b"), mapOf("folder" to listOf("a", "b")))

    @Test fun `network outage preserves on intent tunnel and policy until explicit stop`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val started = controller.state.value
        assertTrue(started.desiredEnabled)
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("old"), 0, "stale"))
        assertNull(controller.state.value.networkReason)
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("tun"), 99, "stale"))
        assertNull(controller.state.value.networkReason)
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("tun"), 0, "Нет сети"))
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("tun"), 0, "Нет сети", recovering = true))
        assertEquals(ExpertTunnelHealth.PENDING, controller.state.value.tunnelHealth)
        assertTrue(controller.state.value.networkRecovering)
        // A real error clears the recovery category even when the displayed reason is unchanged.
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("tun"), 0, "Нет сети"))
        assertFalse(controller.state.value.networkRecovering)
        assertTrue(controller.state.value.desiredEnabled)
        assertEquals(ExpertTunnelHealth.ERROR, controller.state.value.tunnelHealth)
        assertEquals(started.tun, controller.state.value.tun)
        assertEquals(started.appliedPolicy, controller.state.value.appliedPolicy)
        controller.onBackendEvent(ExpertBackendEvent.NetworkStatus(TunIdentity("tun"), 0, null))
        assertEquals(ExpertTunnelHealth.HEALTHY, controller.state.value.tunnelHealth)
        assertFalse(controller.state.value.networkRecovering)
        assertTrue(controller.state.value.desiredEnabled)
        assertEquals(1, backend.starts)
        controller.onBackendEvent(ExpertBackendEvent.TunnelLost(TunIdentity("tun"), "core failed"))
        assertTrue(controller.state.value.desiredEnabled)
        controller.handle(ExpertIntent.Stop)
        assertFalse(controller.state.value.desiredEnabled)
    }


    @Test fun `checked semantic edits preserve queue running policy and durable error evidence`() = runTest {
        var diskFail = false
        val writes = mutableListOf<NetworkPolicy>()
        val backend = FakeBackend()
        val controller = controller(backend, persist = ExpertPolicyPersistence { _, draft ->
            if (diskFail) error("disk unavailable")
            writes += draft
        })
        controller.handle(ExpertIntent.Start)
        val base = controller.state.value.draft
        val applied = controller.state.value.appliedPolicy
        val a = app.lernet.routing.policy.PolicyNode("a", target = PolicyTarget.Block)
        val b = app.lernet.routing.policy.PolicyNode("b", target = PolicyTarget.Direct)
        controller.handle(ExpertIntent.EditChecked(base, base.copy(device = base.device.copy(nodes = listOf(a)))))
        controller.handle(ExpertIntent.EditChecked(base, base.copy(device = base.device.copy(nodes = listOf(b)))))
        assertEquals(setOf("a", "b"), controller.state.value.draft.device.nodes.map { it.id }.toSet())
        assertEquals(applied, controller.state.value.appliedPolicy)
        assertEquals(1, backend.starts)
        val current = controller.state.value.draft
        val next = current.copy(dns = app.lernet.routing.policy.PolicyDnsSettings(app.lernet.routing.policy.PolicyDnsMode.CUSTOM, "9.9.9.9"))
        diskFail = true
        controller.handle(ExpertIntent.EditChecked(current, next))
        assertTrue(controller.state.value.draftPersistenceError != null)
        assertTrue(writes.last() != next)
        diskFail = false
        controller.handle(ExpertIntent.EditChecked(current, next))
        assertNull(controller.state.value.draftPersistenceError)
        assertEquals(next, writes.last())
        assertEquals(applied, controller.state.value.appliedPolicy)
    }

    @Test fun `canvas intent retains newly edited rule and does not restart or apply running tunnel`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val applied = controller.state.value.appliedPolicy
        val node = app.lernet.routing.policy.PolicyNode("new", target = PolicyTarget.Block)
        val current = controller.state.value.draft
        controller.handle(ExpertIntent.Edit(current.copy(device = current.device.copy(nodes = listOf(node)))))
        val point = app.lernet.routing.policy.PolicyCanvasPoint(33f, 44f)
        controller.handle(ExpertIntent.UpdateLayout(PolicyScope.Device, mapOf("new" to point)))
        assertEquals(listOf(node), controller.state.value.draft.device.nodes)
        assertEquals(point, controller.state.value.draft.device.positions["new"])
        controller.handle(ExpertIntent.UpdateLayout(PolicyScope.Device, mapOf("removed" to point)))
        assertEquals(listOf(node), controller.state.value.draft.device.nodes)
        assertFalse("removed" in controller.state.value.draft.device.positions)
        controller.handle(ExpertIntent.UpdateLayout(PolicyScope.Device, clear = true))
        assertEquals(listOf(node), controller.state.value.draft.device.nodes)
        assertTrue(controller.state.value.draft.device.positions.isEmpty())
        assertEquals(applied, controller.state.value.appliedPolicy)
        assertEquals(1, backend.starts)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
    }

    @Test fun `direct family evidence rejects old session revision network and timestamps`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val facts = ExpertDirectNetworkFacts(
            DirectFamilyAvailability.AVAILABLE, DirectFamilyAvailability.UNAVAILABLE, "windows_routes", "Ethernet", 0, 100,
        )
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts, TunIdentity("old"), 0))
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts, TunIdentity("tun"), 99))
        assertNull(controller.state.value.directNetwork)
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts, TunIdentity("tun"), 0))
        assertEquals(facts, controller.state.value.directNetwork)
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts.copy(observedAtMs = 90), TunIdentity("tun"), 0))
        assertEquals(facts, controller.state.value.directNetwork)
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 0, 1))
        assertNull(controller.state.value.directNetwork)
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts, TunIdentity("tun"), 0))
        assertNull(controller.state.value.directNetwork)
        controller.onBackendEvent(ExpertBackendEvent.DirectNetworkSnapshot(facts.copy(networkEpoch = 1), TunIdentity("tun"), 0))
        assertEquals(1L, controller.state.value.directNetwork?.networkEpoch)
    }

    @Test fun `confirmed simple VPN handover persists a profile route before any tunnel acquisition`() = runTest {
        val initial = NetworkPolicy(trees = listOf(PolicyTree(PolicyScope.Profile("a"), defaultTarget = PolicyTarget.CurrentExit)))
        val writes = mutableListOf<NetworkPolicy>()
        val backend = FakeBackend().apply { startAck = ExpertTunnelAck(TunIdentity("tun"), 1) }
        val controller = controller(
            backend, initial,
            ExpertPolicyPersistence { saved, draft ->
                assertEquals(saved, draft)
                writes += saved
            }
        )
        val target = PolicyTarget.Profile("a", routeScope = PolicyScope.Profile("a"))
        assertTrue(controller.prepareVpnHandover(initial, target))
        assertEquals(0, backend.starts)
        assertEquals(target, writes.single().device.defaultTarget)
        assertEquals(initial.trees, writes.single().trees)
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertEquals(target, controller.state.value.appliedPolicy?.device?.defaultTarget)
    }

    @Test fun `handover rejects a stale prompt user draft or configured device path`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        val target = PolicyTarget.Profile("a", routeScope = null)
        val initial = controller.state.value.saved
        assertFalse(controller.prepareVpnHandover(initial.copy(revision = 1), target))
        controller.handle(ExpertIntent.Edit(initial.copy(device = initial.device.copy(defaultTarget = PolicyTarget.Block))))
        assertFalse(controller.prepareVpnHandover(initial, target))
        controller.handle(ExpertIntent.SaveDraft)
        assertFalse(controller.prepareVpnHandover(controller.state.value.saved, target))
        assertEquals(PolicyTarget.Block, controller.state.value.saved.device.defaultTarget)
        assertEquals(0, backend.starts)
    }

    @Test fun `handover persistence failure or missing profile cannot stop the previous VPN`() = runTest {
        val backend = FakeBackend()
        val initial = NetworkPolicy()
        val controller = controller(backend, initial, ExpertPolicyPersistence { _, _ -> error("disk unavailable") })
        assertFalse(controller.prepareVpnHandover(initial, PolicyTarget.Profile("missing", routeScope = null)))
        assertFalse(controller.prepareVpnHandover(initial, PolicyTarget.Profile("a", routeScope = null)))
        assertEquals(initial, controller.state.value.saved)
        assertEquals(initial, controller.state.value.draft)
        assertEquals(0, backend.starts)
        assertTrue(controller.state.value.errors.isNotEmpty())
    }

    @Test fun `restart only host cannot activate expert mode`() = runTest {
        val backend = FakeBackend().apply { actualCapabilities = PolicyControlCapabilities.RESTART_ONLY }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertEquals(0, backend.starts)
        assertNull(controller.state.value.appliedRevision)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertTrue(controller.state.value.errors.isNotEmpty())
    }

    @Test fun `start must acknowledge exact saved revision and a nonblank tunnel`() = runTest {
        val backend = FakeBackend().apply { startAck = ExpertTunnelAck(TunIdentity("tun"), 99) }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertNull(controller.state.value.appliedRevision)
        assertEquals(listOf(TunIdentity("tun")), backend.stops)
    }

    @Test fun `invalid startup acknowledgement with failed cleanup retains ownership and prevents restart`() = runTest {
        val backend = FakeBackend().apply {
            startAck = ExpertTunnelAck(TunIdentity("unconfirmed"), 99)
            stopFailure = "failed cleanup"
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertEquals(TunIdentity("unconfirmed"), controller.state.value.tun)
        assertNull(controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.Start)
        assertEquals(1, backend.starts)
        backend.stopFailure = null
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertNull(controller.state.value.tun)
    }

    @Test fun `blank startup identity with failed cleanup still prevents an unconfirmed second start`() = runTest {
        val backend = FakeBackend().apply {
            startAck = ExpertTunnelAck(TunIdentity(""), 0)
            stopFailure = "failed cleanup"
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertNull(controller.state.value.tun)
        controller.handle(ExpertIntent.Start)
        assertEquals(1, backend.starts)
        backend.stopFailure = null
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
    }

    @Test fun `saved draft does not alter active rules until matching native apply acknowledgement`() = runTest {
        val backend = FakeBackend()
        val writes = mutableListOf<NetworkPolicy>()
        val controller = controller(backend, persist = ExpertPolicyPersistence { saved, _ -> writes += saved })
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.Edit(NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))))
        assertTrue(controller.state.value.hasDraftChanges)
        assertEquals(0L, controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.SaveDraft)
        assertEquals(1L, controller.state.value.saved.revision)
        assertEquals(0L, controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(1L, controller.state.value.appliedRevision)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
        assertEquals(2, writes.size)
    }

    @Test fun `failed persistence cannot advance saved revision or discard draft`() = runTest {
        val controller = controller(FakeBackend(), persist = ExpertPolicyPersistence { _, _ -> error("disk full") })
        controller.handle(ExpertIntent.Edit(NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))))
        controller.handle(ExpertIntent.SaveDraft)
        assertEquals(0L, controller.state.value.saved.revision)
        assertTrue(controller.state.value.hasDraftChanges)
        assertTrue(controller.state.value.errors.single().contains("disk full"))
    }

    @Test fun `inventory refresh and native mode acknowledgements cannot confirm an unwritten draft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { startGate = gate }
        val controller = controller(backend, persist = ExpertPolicyPersistence { _, _ -> error("disk full") })
        val edited = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))
        controller.handle(ExpertIntent.Edit(edited))
        val sticky = checkNotNull(controller.state.value.draftPersistenceError)
        assertTrue(sticky.contains("disk full"))
        controller.handle(ExpertIntent.InventoryChanged(inventory))
        controller.handle(ExpertIntent.WorkspaceChanged(NetworkPolicy(), edited, inventory))
        assertEquals(sticky, controller.state.value.draftPersistenceError)
        assertTrue(sticky in controller.state.value.errors)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        assertEquals(ExpertSessionPhase.STARTING, controller.state.value.phase)
        assertTrue(sticky in controller.state.value.errors)
        gate.complete(Unit)
        startup.await()
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertEquals(0L, controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.ApplySaved)
        assertTrue(sticky in controller.state.value.errors)
        assertEquals(edited, controller.state.value.draft)
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertEquals(sticky, controller.state.value.draftPersistenceError)
        assertTrue(sticky in controller.state.value.errors)
    }

    @Test fun `save without draft changes retries a failed write before clearing persistence failure`() = runTest {
        var broken = true
        var writes = 0
        val controller = controller(
            FakeBackend(),
            persist = ExpertPolicyPersistence { _, _ ->
                writes++
                check(!broken) { "disk full" }
            }
        )
        controller.handle(ExpertIntent.Edit(NetworkPolicy()))
        assertFalse(controller.state.value.hasDraftChanges)
        assertTrue(controller.state.value.draftPersistenceError != null)
        broken = false
        controller.handle(ExpertIntent.SaveDraft)
        assertEquals(2, writes)
        assertEquals(0L, controller.state.value.saved.revision)
        assertNull(controller.state.value.draftPersistenceError)
        assertTrue(controller.state.value.errors.isEmpty())
    }

    @Test fun `durable save clears persistence failure while active native policy stays unchanged`() = runTest {
        var broken = true
        val controller = controller(
            FakeBackend(),
            persist = ExpertPolicyPersistence { _, _ ->
                check(!broken) { "disk full" }
            }
        )
        controller.handle(ExpertIntent.Start)
        val applied = controller.state.value.appliedPolicy
        controller.handle(
            ExpertIntent.Edit(
                NetworkPolicy(
                    device = PolicyTree(
                        PolicyScope.Device,
                        defaultTarget = PolicyTarget.Block
                    )
                )
            )
        )
        assertTrue(controller.state.value.draftPersistenceError != null)
        broken = false
        controller.handle(ExpertIntent.SaveDraft)
        assertNull(controller.state.value.draftPersistenceError)
        assertTrue(controller.state.value.errors.isEmpty())
        assertEquals(1L, controller.state.value.saved.revision)
        assertEquals(0L, controller.state.value.appliedRevision)
        assertEquals(applied, controller.state.value.appliedPolicy)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
    }

    @Test fun `failed atomic apply retains prior revision and redacts backend errors`() = runTest {
        val backend = FakeBackend().apply { applyFailure = "password\":\"secret\" vless://bad@server pbk=secret" }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.Edit(NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))))
        controller.handle(ExpertIntent.SaveDraft)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(0L, controller.state.value.appliedRevision)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertFalse(controller.state.value.reasons.joinToString { it.message }.contains("vless://bad"))
        assertFalse(controller.state.value.reasons.joinToString { it.message }.contains("pbk=secret"))
    }

    @Test fun `exact native failure is human readable platform neutral while unknown failures retain redaction`() = runTest {
        val backend = FakeBackend().apply { applyFailure = "tun_identity_unavailable" }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.ApplySaved)
        val known = controller.state.value.errors.single()
        assertTrue(known.contains("Не удалось подтвердить собственный адаптер TUN"))
        assertFalse(known.contains("Windows"))
        assertFalse(known.contains("tun_identity_unavailable"))
        val unknown = "Unknown native error " + "x".repeat(200)
        backend.applyFailure = "$unknown \"password\":\"private\" vless://private@server"
        controller.handle(ExpertIntent.ApplySaved)
        val safeUnknown = controller.state.value.errors.single()
        assertTrue(safeUnknown.contains(unknown))
        assertFalse(safeUnknown.contains("private"))
    }

    @Test fun `changed interface identity invalidates active revision`() = runTest {
        val backend = FakeBackend().apply { applyIdentity = TunIdentity("tun", "replacement") }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertNull(controller.state.value.appliedRevision)
    }

    @Test fun `wrong apply revision exposes the cause to the screen without claiming an active policy`() = runTest {
        val backend = FakeBackend().apply { applyRevision = 99 }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val edited = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))
        controller.handle(ExpertIntent.Edit(edited))
        controller.handle(ExpertIntent.SaveDraft)
        controller.handle(ExpertIntent.ApplySaved)

        val snapshot = controller.state.value
        assertEquals(ExpertSessionPhase.FAILED, snapshot.phase)
        assertEquals(1L, snapshot.saved.revision)
        assertEquals(snapshot.saved, snapshot.draft)
        assertNull(snapshot.appliedRevision)
        assertNull(snapshot.appliedPolicy)
        assertFalse(snapshot.applying)
        assertTrue(snapshot.errors.single().contains("Ядро сообщило другую версию"))
        assertEquals(snapshot.errors.single(), snapshot.reasons.last().message)
    }

    @Test fun `cancelled apply exposes uncertainty and preserves the saved draft`() = runTest {
        val backend = FakeBackend().apply { applyGate = CompletableDeferred() }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val edited = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))
        controller.handle(ExpertIntent.Edit(edited))
        controller.handle(ExpertIntent.SaveDraft)
        val applying = async { controller.handle(ExpertIntent.ApplySaved) }
        runCurrent()
        assertTrue(controller.state.value.applying)
        applying.cancel()
        applying.join()

        val snapshot = controller.state.value
        assertEquals(ExpertSessionPhase.FAILED, snapshot.phase)
        assertEquals(1L, snapshot.saved.revision)
        assertEquals(snapshot.saved, snapshot.draft)
        assertNull(snapshot.appliedRevision)
        assertNull(snapshot.appliedPolicy)
        assertFalse(snapshot.applying)
        assertTrue(snapshot.errors.single().contains("Применение прервано"))
        assertEquals(snapshot.errors.single(), snapshot.reasons.last().message)
    }

    @Test fun `late start completion after stop cannot reactivate tunnel`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { startGate = gate }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        assertEquals(ExpertSessionPhase.STARTING, controller.state.value.phase)
        controller.handle(ExpertIntent.Stop)
        gate.complete(Unit)
        startup.await()
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertNull(controller.state.value.appliedRevision)
        assertTrue(backend.stops.contains(TunIdentity("tun")))
    }

    @Test fun `cancelled startup releases owned backend without leaving starting phase`() = runTest {
        val backend = FakeBackend().apply { startGate = CompletableDeferred() }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        assertEquals(ExpertSessionPhase.STARTING, controller.state.value.phase)
        startup.cancel()
        startup.join()
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertNull(controller.state.value.appliedRevision)
        assertEquals(listOf<TunIdentity?>(null), backend.stops)
    }

    @Test fun `cancelled capability negotiation releases control host before any tunnel starts`() = runTest {
        val backend = FakeBackend().apply { negotiateGate = CompletableDeferred() }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        startup.cancel()
        startup.join()
        assertEquals(0, backend.starts)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertEquals(listOf<TunIdentity?>(null), backend.stops)
    }

    @Test fun `unconfirmed cancellation cleanup requires explicit stop before a new start`() = runTest {
        val backend = FakeBackend().apply {
            startGate = CompletableDeferred()
            stopFailure = "host still running"
        }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        startup.cancel()
        startup.join()
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        controller.handle(ExpertIntent.Start)
        assertEquals(1, backend.starts)
        backend.stopFailure = null
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
    }

    @Test fun `cancelled stop leaves retryable unconfirmed state instead of permanent stopping`() = runTest {
        val backend = FakeBackend().apply { stopGate = CompletableDeferred() }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val stopping = async { controller.handle(ExpertIntent.Stop) }
        runCurrent()
        assertEquals(ExpertSessionPhase.STOPPING, controller.state.value.phase)
        stopping.cancel()
        stopping.join()
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertNull(controller.state.value.appliedRevision)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
        backend.stopGate = null
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.STOPPED, controller.state.value.phase)
        assertNull(controller.state.value.tun)
    }

    @Test fun `failed explicit stop during startup blocks restart until cleanup is confirmed`() = runTest {
        val backend = FakeBackend().apply {
            startGate = CompletableDeferred()
            stopFailure = "cleanup not confirmed"
        }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        controller.handle(ExpertIntent.Stop)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertNull(controller.state.value.tun)
        val retry = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        assertEquals(1, backend.starts)
        retry.await()
        startup.cancel()
        startup.join()
        backend.stopFailure = null
        backend.startGate = null
        controller.handle(ExpertIntent.Stop)
        controller.handle(ExpertIntent.Start)
        assertEquals(2, backend.starts)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
    }

    @Test fun `cancelled explicit stop before startup identity blocks restart until cleanup is confirmed`() = runTest {
        val backend = FakeBackend().apply {
            startGate = CompletableDeferred()
            stopGate = CompletableDeferred()
        }
        val controller = controller(backend)
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        val stopping = async { controller.handle(ExpertIntent.Stop) }
        runCurrent()
        stopping.cancel()
        stopping.join()
        backend.stopGate!!.complete(Unit)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertNull(controller.state.value.tun)
        val retry = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        assertEquals(1, backend.starts)
        retry.await()
        startup.cancel()
        startup.join()
        backend.stopGate = null
        backend.startGate = null
        controller.handle(ExpertIntent.Stop)
        controller.handle(ExpertIntent.Start)
        assertEquals(2, backend.starts)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
    }

    @Test fun `two first flows coalesce one independent wake and both are admitted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { wakeGate = gate }
        val controller = controller(backend, initial = coldPolicy())
        controller.handle(ExpertIntent.Start)
        val exit = PolicyExit(PolicyTarget.Profile("a", routeScope = null), "channel")
        val first = async { controller.requestExit(exit, "first") }
        val second = async { controller.requestExit(exit, "second") }
        runCurrent()
        assertEquals(1, backend.wakes.size)
        assertEquals(2, controller.state.value.exits.single().pendingFlows)
        gate.complete(Unit)
        assertTrue(first.await() is ExpertExitResolution.Ready)
        assertTrue(second.await() is ExpertExitResolution.Ready)
        assertEquals(2, controller.state.value.exits.single().activeFlows)
    }

    @Test fun `bounded pending queue refuses excess flow without direct fallback`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { wakeGate = gate }
        val controller = controller(backend, initial = coldPolicy(maxPending = 1))
        controller.handle(ExpertIntent.Start)
        val exit = PolicyExit(PolicyTarget.Profile("a", routeScope = null), "channel")
        val first = async { controller.requestExit(exit, "first") }
        runCurrent()
        assertTrue(controller.requestExit(exit, "excess") is ExpertExitResolution.Blocked)
        gate.complete(Unit)
        first.await()
        assertEquals(1, backend.wakes.size)
    }

    @Test fun `first flow deadline rejects while wake may finish without it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { wakeGate = gate }
        val controller = controller(backend, initial = coldPolicy(firstFlowMs = 1_000, startupMs = 5_000))
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        val request = async { controller.requestExit(PolicyExit(PolicyTarget.Profile("a", routeScope = null), "channel"), "flow") }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(request.await() is ExpertExitResolution.Blocked)
        assertEquals(0, controller.state.value.exits.single().pendingFlows)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ExitPhase.READY, controller.state.value.exits.single().phase)
        assertEquals(0, controller.state.value.exits.single().activeFlows)
    }

    @Test fun `quiet open connections keep cold exit alive and probe never resets idle`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend, initial = coldPolicy(idleMs = 1_000))
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        val key = ExpertExitKey("a", "channel")
        val resolution = controller.requestExit(PolicyExit(PolicyTarget.Profile("a", routeScope = null), "channel"), "flow")
        assertTrue(resolution is ExpertExitResolution.Ready)
        advanceTimeBy(2_000)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertTrue(backend.sleeps.isEmpty())
        controller.onBackendEvent(ExpertBackendEvent.FlowClosed(key, "flow"))
        advanceTimeBy(1_001)
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(25)))
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(key), backend.sleeps)
        assertEquals(ExitPhase.SLEEPING, controller.state.value.exits.single().phase)
    }

    @Test fun `folder chooses actual healthy HTTPS exit and keeps it despite faster neighbor`() = runTest {
        val backend = FakeBackend().apply {
            probeResults[ExpertExitKey("a")] = ExpertProbeResult(100)
            probeResults[ExpertExitKey("b")] = ExpertProbeResult(50)
        }
        val policy = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", FolderSelection.LOWEST_LATENCY, autoSwap = true)))
        val controller = controller(backend, initial = policy)
        controller.handle(ExpertIntent.Start)
        val target = PolicyExit(PolicyTarget.Folder("folder", routeScope = null))
        val first = controller.requestExit(target, "flow") as ExpertExitResolution.Ready
        assertEquals("b", first.key.profileId)
        controller.onBackendEvent(ExpertBackendEvent.Health(ExpertExitKey("a", folderId = "folder"), ExpertProbeResult(10)))
        val next = controller.requestExit(target, "next") as ExpertExitResolution.Ready
        assertEquals("b", next.key.profileId)
    }

    @Test fun `dead active folder exit swaps after two failed checks without replacing TUN`() = runTest {
        val backend = FakeBackend().apply {
            probeResults[ExpertExitKey("a")] = ExpertProbeResult(40)
            probeResults[ExpertExitKey("b")] = ExpertProbeResult(50)
        }
        val policy = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", autoSwap = true)))
        val controller = controller(backend, initial = policy)
        controller.handle(ExpertIntent.Start)
        val target = PolicyExit(PolicyTarget.Folder("folder", routeScope = null))
        assertEquals("a", (controller.requestExit(target, "one") as ExpertExitResolution.Ready).key.profileId)
        backend.probeResults[ExpertExitKey("a")] = ExpertProbeResult(null, "still dead after reset")
        controller.onBackendEvent(ExpertBackendEvent.Health(ExpertExitKey("a", folderId = "folder"), ExpertProbeResult(null, "dead")))
        controller.onBackendEvent(ExpertBackendEvent.Health(ExpertExitKey("a", folderId = "folder"), ExpertProbeResult(null, "dead")))
        assertEquals("b", (controller.requestExit(target, "two") as ExpertExitResolution.Ready).key.profileId)
        assertEquals(1, backend.starts)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
        assertTrue(backend.recoveries.any { it.profileId == "a" })
    }

    @Test fun `dead folder current exit recovers before a healthy neighbor is selected`() = runTest {
        val backend = FakeBackend()
        val policy = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", autoSwap = true)))
        val controller = controller(backend, initial = policy)
        controller.handle(ExpertIntent.Start)
        val target = PolicyExit(PolicyTarget.Folder("folder", routeScope = null))
        val first = (controller.requestExit(target, "one") as ExpertExitResolution.Ready).key
        assertEquals("a", first.profileId)
        repeat(2) { controller.onBackendEvent(ExpertBackendEvent.Health(first, ExpertProbeResult(null, "dead"))) }
        assertEquals("a", (controller.requestExit(target, "two") as ExpertExitResolution.Ready).key.profileId)
        assertEquals(listOf(first), backend.recoveries)
        assertEquals(1, backend.starts)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
    }

    @Test fun `native health authority disables duplicate periodic Kotlin probes`() = runTest {
        val backend = FakeBackend().apply { actualCapabilities = PolicyControlCapabilities(true, true, true, true) }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(ExpertExitKey("a"), ExitPhase.READY)))
        advanceTimeBy(7_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertTrue(backend.probeTimeouts.isEmpty())
        assertTrue(backend.recoveries.isEmpty())
    }

    @Test fun `shared health proof preserves factual native epoch timestamp instead of monotonic time`() = runTest {
        val backend = FakeBackend().apply { actualCapabilities = PolicyControlCapabilities(true, true, true, true) }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        val native = ExpertExitState(key, ExitPhase.READY, lastCheckMs = 1_700_000_000_000)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(native))
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(15)))
        assertEquals(native.lastCheckMs, controller.state.value.exits.single().lastCheckMs)
        repeat(2) { controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null))) }
        val refreshed = native.copy(lastCheckMs = 1_700_000_000_500)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(refreshed))
        assertEquals(ExitPhase.DEGRADED, controller.state.value.exits.single().phase)
        assertEquals(refreshed.lastCheckMs, controller.state.value.exits.single().lastCheckMs)
    }

    @Test fun `repository workspace refresh preserves confirmed applied revision`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val updated = NetworkPolicy(revision = 3)
        controller.handle(ExpertIntent.WorkspaceChanged(updated, updated, inventory))
        assertEquals(3L, controller.state.value.saved.revision)
        assertEquals(0L, controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.WorkspaceChanged(NetworkPolicy(revision = 1), NetworkPolicy(revision = 1), inventory))
        assertEquals(3L, controller.state.value.saved.revision)
    }

    @Test fun `native exit status uses actual queue counts and cannot manufacture user flows`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.STARTING, pendingFlows = 3)))
        assertEquals(3, controller.state.value.exits.single().pendingFlows)
        assertEquals(0, controller.state.value.exits.single().activeFlows)
    }

    @Test fun `capabilities negotiated before ingress can enable truthful native host`() = runTest {
        val backend = FakeBackend().apply {
            actualCapabilities = PolicyControlCapabilities.RESTART_ONLY
            negotiatedCapabilities = PolicyControlCapabilities(true, true, true)
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertEquals(1, backend.starts)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertTrue(controller.state.value.capabilities.preservesTun)
    }

    @Test fun `uncertain apply cannot claim previous revision still active`() = runTest {
        val backend = FakeBackend().apply { applyProblem = ExpertStateUncertainException("commit response lost") }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.ApplySaved)
        assertNull(controller.state.value.appliedRevision)
        assertEquals(ExpertSessionPhase.FAILED, controller.state.value.phase)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
    }

    @Test fun `external first flow cancellation removes abandoned queue entry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { wakeGate = gate }
        val controller = controller(backend, initial = coldPolicy())
        controller.handle(ExpertIntent.Start)
        val request = async { controller.requestExit(PolicyExit(PolicyTarget.Profile("a", routeScope = null), "channel"), "flow") }
        runCurrent()
        request.cancel()
        runCurrent()
        assertEquals(0, controller.state.value.exits.single().pendingFlows)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, controller.state.value.exits.single().activeFlows)
    }

    @Test fun `healthy preferred candidate does not wait for hanging neighbor`() = runTest {
        val backend = FakeBackend().apply {
            probeGates[ExpertExitKey("b")] = CompletableDeferred()
            probeResults[ExpertExitKey("a")] = ExpertProbeResult(20)
        }
        val policy = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", autoSwap = true)))
        val controller = controller(backend, initial = policy)
        controller.handle(ExpertIntent.Start)
        val request = async { controller.requestExit(PolicyExit(PolicyTarget.Folder("folder", routeScope = null)), "flow") }
        runCurrent()
        assertTrue(request.isCompleted)
        assertEquals("a", (request.await() as ExpertExitResolution.Ready).key.profileId)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `single failed check does not prematurely swap healthy current folder exit`() = runTest {
        val policy = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", autoSwap = true)))
        val controller = controller(FakeBackend(), initial = policy)
        controller.handle(ExpertIntent.Start)
        val target = PolicyExit(PolicyTarget.Folder("folder", routeScope = null))
        assertEquals("a", (controller.requestExit(target, "one") as ExpertExitResolution.Ready).key.profileId)
        controller.onBackendEvent(ExpertBackendEvent.Health(ExpertExitKey("a", folderId = "folder"), ExpertProbeResult(null)))
        assertEquals("a", (controller.requestExit(target, "two") as ExpertExitResolution.Ready).key.profileId)
    }

    @Test fun `network change refuses a late probe result from old network`() = runTest {
        val key = ExpertExitKey("a")
        val backend = FakeBackend().apply {
            probeGates[key] = CompletableDeferred()
            cancelledProbeResult = ExpertProbeResult(12)
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        advanceTimeBy(3_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        controller.handle(ExpertIntent.NetworkChanged)
        runCurrent()
        assertNull(controller.state.value.exits.single().lastCheckMs)
        assertNull(controller.state.value.exits.single().latencyMs)
    }

    @Test fun `native network change rejects old probe completion without replacing tunnel or policy`() = runTest {
        val key = ExpertExitKey("a")
        val backend = FakeBackend().apply {
            probeGates[key] = CompletableDeferred()
            cancelledProbeResult = ExpertProbeResult(12)
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY, 22, lastCheckMs = 100)))
        advanceTimeBy(3_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 0, 1))
        runCurrent()
        assertNull(controller.state.value.exits.single().lastCheckMs)
        assertNull(controller.state.value.exits.single().latencyMs)
        assertEquals(TunIdentity("tun"), controller.state.value.tun)
        assertEquals(0L, controller.state.value.appliedRevision)
        assertTrue(backend.recoveries.isEmpty())
        assertTrue(backend.wakes.isEmpty())
    }

    @Test fun `native network epochs are revision fenced monotonic and do not start duplicate health work`() = runTest {
        val backend = FakeBackend().apply { actualCapabilities = PolicyControlCapabilities(true, true, true, true) }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        val native = ExpertExitState(key, ExitPhase.READY, 33, lastCheckMs = 1_700_000_000_000)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(native, TunIdentity("tun"), 0))
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("old"), 0, 100))
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 99, 100))
        assertEquals(native, controller.state.value.exits.single())
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 0, 2))
        assertNull(controller.state.value.exits.single().latencyMs)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(native, TunIdentity("tun"), 0))
        val reasons = controller.state.value.reasons.size
        listOf(2L, 1L, -1L).forEach { epoch ->
            controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 0, epoch))
        }
        assertEquals(native, controller.state.value.exits.single())
        assertEquals(reasons, controller.state.value.reasons.size)
        advanceTimeBy(7_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertTrue(backend.probeTimeouts.isEmpty())
        assertTrue(backend.recoveries.isEmpty())
        assertTrue(backend.wakes.isEmpty())
    }

    @Test fun `network change resets failed check streak without declaring failure from the change alone`() = runTest {
        val backend = FakeBackend().apply { actualCapabilities = PolicyControlCapabilities(true, true, true, true) }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "old network failure")))
        controller.onBackendEvent(ExpertBackendEvent.NetworkChanged(TunIdentity("tun"), 0, 1))
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "first new network failure")))
        assertEquals(ExitPhase.READY, controller.state.value.exits.single().phase)
        assertTrue(backend.recoveries.isEmpty())
    }

    @Test fun `failed retirement is historical deduplicated and cannot mark a new same exit unhealthy`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        controller.handle(
            ExpertIntent.Edit(
                NetworkPolicy(
                    device = PolicyTree(
                        PolicyScope.Device,
                        defaultTarget = PolicyTarget.Block
                    )
                )
            )
        )
        controller.handle(ExpertIntent.SaveDraft)
        controller.handle(ExpertIntent.ApplySaved)
        val ready = ExpertExitState(ExpertExitKey("a"), ExitPhase.READY, 20)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ready, TunIdentity("tun"), 1))
        val failures = listOf(ExpertRetiredCleanupFailure(0, "exit_stop_failed", listOf("old-physical-a")))
        val event = ExpertBackendEvent.RetiredCleanupSnapshot(failures, TunIdentity("tun"), 1)
        repeat(3) { controller.onBackendEvent(event) }
        controller.onBackendEvent(event.copy(identity = TunIdentity("old"), failures = emptyList()))
        controller.onBackendEvent(event.copy(revision = 0, failures = emptyList()))
        assertEquals(failures, controller.state.value.retiredCleanupFailures)
        assertEquals(1, controller.state.value.reasons.count { it.level == ExpertReasonLevel.ERROR })
        assertEquals(ready, controller.state.value.exits.single())
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertEquals(1L, controller.state.value.appliedRevision)
        controller.onBackendEvent(event.copy(failures = emptyList()))
        assertTrue(controller.state.value.retiredCleanupFailures.isEmpty())
        controller.onBackendEvent(event)
        assertEquals(2, controller.state.value.reasons.count { it.level == ExpertReasonLevel.ERROR })
    }

    @Test fun `late probe cannot resurrect a factual sleeping exit`() = runTest {
        val key = ExpertExitKey("a")
        val backend = FakeBackend().apply {
            probeGates[key] = CompletableDeferred()
            cancelledProbeResult = ExpertProbeResult(12)
        }
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        advanceTimeBy(3_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.SLEEPING)))
        runCurrent()
        assertEquals(ExitPhase.SLEEPING, controller.state.value.exits.single().phase)
        assertNull(controller.state.value.exits.single().lastCheckMs)
        assertNull(controller.state.value.exits.single().latencyMs)
    }

    @Test fun `actual folder selection snapshots preserve context and clear obsolete choices`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("b", "inner", listOf("outer", "inner"), "folder")
        controller.onBackendEvent(ExpertBackendEvent.FolderSelectionsSnapshot(mapOf("native-folder-tag" to key), TunIdentity("tun")))
        assertEquals(mapOf("native-folder-tag" to key), controller.state.value.actualFolderSelections)
        controller.onBackendEvent(ExpertBackendEvent.FolderSelectionsSnapshot(emptyMap(), TunIdentity("old")))
        assertEquals(1, controller.state.value.actualFolderSelections.size)
        controller.onBackendEvent(ExpertBackendEvent.FolderSelectionsSnapshot(emptyMap(), TunIdentity("tun")))
        assertTrue(controller.state.value.actualFolderSelections.isEmpty())
    }

    @Test fun `snapshot from previous revision cannot overwrite new exits with the same tunnel identity`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.Edit(NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))))
        controller.handle(ExpertIntent.SaveDraft)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(1L, controller.state.value.appliedRevision)
        val current = ExpertExitState(ExpertExitKey("b"), ExitPhase.READY, activeFlows = 2)
        controller.onBackendEvent(ExpertBackendEvent.ExitsSnapshot(listOf(current), TunIdentity("tun"), revision = 1))
        controller.onBackendEvent(
            ExpertBackendEvent.ExitStatus(current.copy(activeFlows = 99), TunIdentity("tun"), revision = 0),
        )
        controller.onBackendEvent(ExpertBackendEvent.ExitsSnapshot(emptyList(), TunIdentity("tun"), revision = 0))
        controller.onBackendEvent(
            ExpertBackendEvent.FolderSelectionsSnapshot(mapOf("old" to ExpertExitKey("a")), TunIdentity("tun"), revision = 0),
        )
        assertEquals(listOf(current), controller.state.value.exits)
        assertTrue(controller.state.value.actualFolderSelections.isEmpty())
        val history = ExpertConnectionObservation(
            "old-flow", destination = "example.invalid", protocol = "tcp",
            decision = "Old rule", policyRevision = 0
        )
        controller.onBackendEvent(ExpertBackendEvent.Observation(history, TunIdentity("tun")))
        assertEquals(0L, controller.state.value.connections.single().policyRevision)
    }

    @Test fun `different logical paths into the same final channel share one physical exit`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend, initial = coldPolicy())
        controller.handle(ExpertIntent.Start)
        val profile = PolicyTarget.Profile("a", routeScope = null)
        val left = PolicyExit(profile, "channel", listOf("left", "channel"))
        val right = PolicyExit(profile, "channel", listOf("right", "channel"))
        val leftReady = controller.requestExit(left, "left-flow") as ExpertExitResolution.Ready
        val rightReady = controller.requestExit(right, "right-flow") as ExpertExitResolution.Ready
        assertEquals(leftReady.key, rightReady.key)
        assertEquals(1, backend.wakes.size)
        assertEquals(listOf("channel"), backend.wakes.single().channelPath)
        assertEquals(2, controller.state.value.exits.single().activeFlows)
        assertEquals(listOf("left", "channel"), left.channelPath)
        assertEquals(listOf("right", "channel"), right.channelPath)
    }

    @Test fun `successful native probe restores degraded exit display`() = runTest {
        val controller = controller(FakeBackend())
        val key = ExpertExitKey("a")
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.DEGRADED)))
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(15)))
        assertEquals(ExitPhase.READY, controller.state.value.exits.single().phase)
    }

    @Test fun `resource ready poll cannot erase failed HTTPS health until successful probe`() = runTest {
        val controller = controller(FakeBackend())
        val key = ExpertExitKey("a")
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        repeat(2) { controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "No HTTPS reply"))) }
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        assertEquals(ExitPhase.DEGRADED, controller.state.value.exits.single().phase)
        assertEquals("No HTTPS reply", controller.state.value.exits.single().reason)
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(15)))
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        assertEquals(ExitPhase.READY, controller.state.value.exits.single().phase)
    }

    @Test fun `events from another native instance cannot contaminate active session`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        controller.onBackendEvent(
            ExpertBackendEvent.ExitStatus(ExpertExitState(ExpertExitKey("a"), ExitPhase.READY), TunIdentity("old")),
        )
        assertTrue(controller.state.value.exits.isEmpty())
        assertEquals(0L, controller.state.value.appliedRevision)
    }

    @Test fun `saved policy cannot change running folder preference before apply acknowledgement`() = runTest {
        val original = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder", preferredProfileId = "a", autoSwap = true)))
        val backend = FakeBackend()
        val controller = controller(backend, initial = original)
        controller.handle(ExpertIntent.Start)
        val modified = original.copy(folderPolicies = listOf(FolderPolicy("folder", preferredProfileId = "b", autoSwap = true)))
        controller.handle(ExpertIntent.Edit(modified))
        controller.handle(ExpertIntent.SaveDraft)
        val target = PolicyExit(PolicyTarget.Folder("folder", routeScope = null))
        assertEquals("a", (controller.requestExit(target, "first") as ExpertExitResolution.Ready).key.profileId)
        assertEquals(original, controller.state.value.appliedPolicy)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals("b", (controller.requestExit(target, "second") as ExpertExitResolution.Ready).key.profileId)
        assertEquals(1L, controller.state.value.appliedPolicy?.revision)
    }

    @Test fun `startup acknowledgement remains valid after newer workspace data arrives`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(FakeBackend().apply { startGate = gate })
        val startup = async { controller.handle(ExpertIntent.Start) }
        runCurrent()
        val next = NetworkPolicy(revision = 3)
        controller.handle(ExpertIntent.WorkspaceChanged(next, next, inventory))
        gate.complete(Unit)
        startup.await()
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        assertEquals(0L, controller.state.value.appliedRevision)
        assertEquals(3L, controller.state.value.saved.revision)
    }

    @Test fun `native active probe receives short real network timeout`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        advanceTimeBy(3_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(4_000L, backend.probeTimeouts.single())
    }

    @Test fun `health bounds change only after successful apply acknowledgement`() = runTest {
        val backend = FakeBackend()
        val initial = NetworkPolicy(health = PolicyHealthSettings(5_000, 5_000, 6_000, 3))
        val controller = controller(backend, initial = initial)
        controller.handle(ExpertIntent.Start)
        assertEquals(ExpertSessionPhase.RUNNING, controller.state.value.phase)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        advanceTimeBy(4_999)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertTrue(backend.probeTimeouts.isEmpty())
        advanceTimeBy(2)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(6_000L), backend.probeTimeouts)
        val updated = initial.copy(health = PolicyHealthSettings(9_000, 9_000, 8_000, 1))
        controller.handle(ExpertIntent.Edit(updated))
        controller.handle(ExpertIntent.SaveDraft)
        advanceTimeBy(5_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(6_000L, 6_000L), backend.probeTimeouts)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(1L, controller.state.value.appliedRevision)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(6_000L, 6_000L, 8_000L), backend.probeTimeouts)
        advanceTimeBy(8_999)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(3, backend.probeTimeouts.size)
        advanceTimeBy(2)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(6_000L, 6_000L, 8_000L, 8_000L), backend.probeTimeouts)
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "failed current check")))
        assertEquals(ExitPhase.DEGRADED, controller.state.value.exits.single().phase)
    }

    @Test fun `failed apply retains previous health interval timeout and recovery threshold`() = runTest {
        val backend = FakeBackend().apply { applyFailure = "atomic reject" }
        val initial = NetworkPolicy(health = PolicyHealthSettings(5_000, 5_000, 6_000, 3))
        val controller = controller(backend, initial = initial)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        controller.handle(ExpertIntent.Edit(initial.copy(health = PolicyHealthSettings(1_000, 1_000, 1_000, 1))))
        controller.handle(ExpertIntent.SaveDraft)
        controller.handle(ExpertIntent.ApplySaved)
        assertEquals(0L, controller.state.value.appliedRevision)
        advanceTimeBy(1_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertTrue(backend.probeTimeouts.isEmpty())
        advanceTimeBy(4_000)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(6_000L), backend.probeTimeouts)
        repeat(2) { controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "failed check"))) }
        assertEquals(ExitPhase.READY, controller.state.value.exits.single().phase)
        assertTrue(backend.recoveries.isEmpty())
    }

    @Test fun `explicit probe schedule override wins over portable policy health settings`() = runTest {
        val backend = FakeBackend()
        val initial = NetworkPolicy(health = PolicyHealthSettings(9_000, 9_000, 8_000, 3))
        val scheduleOverride = ExpertProbeSchedule(1_000, 1_000, 2_000, failedChecksBeforeRecovery = 1)
        val controller = controller(backend, initial = initial, probeSchedule = scheduleOverride)
        controller.handle(ExpertIntent.Start)
        val key = ExpertExitKey("a")
        controller.onBackendEvent(ExpertBackendEvent.ExitStatus(ExpertExitState(key, ExitPhase.READY)))
        advanceTimeBy(1_001)
        controller.handle(ExpertIntent.Tick)
        runCurrent()
        assertEquals(listOf(2_000L), backend.probeTimeouts)
        controller.onBackendEvent(ExpertBackendEvent.Health(key, ExpertProbeResult(null, "failed check")))
        assertEquals(ExitPhase.DEGRADED, controller.state.value.exits.single().phase)
    }

    @Test fun `actual native snapshot removes retired exits instead of resurrecting local state`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        controller.handle(ExpertIntent.WakeExit(ExpertExitKey("a")))
        controller.onBackendEvent(ExpertBackendEvent.ExitsSnapshot(listOf(ExpertExitState(ExpertExitKey("a"), ExitPhase.READY))))
        controller.onBackendEvent(ExpertBackendEvent.ExitsSnapshot(emptyList()))
        assertTrue(controller.state.value.exits.isEmpty())
    }

    @Test fun `profile policy configures first flow deadline without requiring a named channel`() = runTest {
        val backend = FakeBackend().apply { wakeGate = CompletableDeferred() }
        val policy = NetworkPolicy(profilePolicies = listOf(ProfileExitPolicy("a", ExitLifecyclePolicy(true, firstFlowTimeoutMs = 1_000))))
        val controller = controller(backend, initial = policy)
        controller.handle(ExpertIntent.Start)
        val request = async { controller.requestExit(PolicyExit(PolicyTarget.Profile("a", routeScope = null)), "flow") }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(request.await() is ExpertExitResolution.Blocked)
    }

    @Test fun `same profile inside a folder and standalone use independent physical contexts`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend, initial = NetworkPolicy(folderPolicies = listOf(FolderPolicy("folder"))))
        controller.handle(ExpertIntent.Start)
        val folder = controller.requestExit(PolicyExit(PolicyTarget.Folder("folder", routeScope = null)), "folder-flow")
        val standalone = controller.requestExit(PolicyExit(PolicyTarget.Profile("a", routeScope = null)), "direct-profile-flow")
        assertEquals("folder", (folder as ExpertExitResolution.Ready).key.folderId)
        assertNull((standalone as ExpertExitResolution.Ready).key.folderId)
        assertTrue(backend.wakes.contains(ExpertExitKey("a", folderId = "folder")))
        assertTrue(backend.wakes.contains(ExpertExitKey("a")))
    }

    @Test fun `connection history cap preserves quiet active connections before completed records`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val active = ExpertConnectionObservation(
            "active", destination = "example.invalid", protocol = "tcp", decision = "Direct", active = true,
        )
        controller.onBackendEvent(ExpertBackendEvent.Observation(active, TunIdentity("tun")))
        repeat(500) { index ->
            controller.onBackendEvent(
                ExpertBackendEvent.Observation(active.copy(id = "closed-$index", active = false), TunIdentity("tun")),
            )
        }
        assertEquals(500, controller.state.value.connections.size)
        assertTrue(controller.state.value.connections.any { it.id == "active" && it.active == true })
        assertTrue(controller.state.value.connectionHistoryTruncated)
        assertEquals(1L, controller.state.value.connectionDroppedCount)
    }

    @Test fun `native eviction makes missing live connection activity unknown without inventing closure`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val flow = ExpertConnectionObservation(
            "flow", destination = "example.invalid", protocol = "tcp", decision = "Direct", active = true,
        )
        controller.onBackendEvent(ExpertBackendEvent.Observation(flow, TunIdentity("tun")))
        controller.onBackendEvent(ExpertBackendEvent.ObservationHistory(1, identity = TunIdentity("old"), visibleFlowIds = emptySet()))
        assertTrue(controller.state.value.connections.single().active == true)
        controller.onBackendEvent(ExpertBackendEvent.ObservationHistory(1, identity = TunIdentity("tun"), visibleFlowIds = emptySet()))
        assertNull(controller.state.value.connections.single().active)
        assertNull(controller.state.value.connections.single().closedAtMs)
        assertTrue(controller.state.value.connectionHistoryTruncated)
    }

    @Test fun `connection ids are scoped to native session and stale events cannot overwrite new rows`() = runTest {
        val backend = FakeBackend()
        val controller = controller(backend)
        controller.handle(ExpertIntent.Start)
        val flow = ExpertConnectionObservation(
            "1", destination = "old.invalid", protocol = "tcp", decision = "Direct", active = true,
        )
        controller.onBackendEvent(ExpertBackendEvent.Observation(flow, TunIdentity("tun")))
        controller.onBackendEvent(ExpertBackendEvent.ObservationHistory(10, identity = TunIdentity("tun")))
        assertTrue(controller.state.value.connectionHistoryTruncated)
        controller.handle(ExpertIntent.Stop)
        assertNull(controller.state.value.connections.single().active)
        backend.startAck = ExpertTunnelAck(TunIdentity("new"), 0)
        controller.handle(ExpertIntent.Start)
        assertTrue(controller.state.value.connections.isEmpty())
        assertEquals(0L, controller.state.value.connectionDroppedCount)
        assertFalse(controller.state.value.connectionHistoryTruncated)
        controller.onBackendEvent(ExpertBackendEvent.Observation(flow.copy(destination = "new.invalid"), TunIdentity("new")))
        controller.onBackendEvent(ExpertBackendEvent.Observation(flow.copy(destination = "late.invalid"), TunIdentity("tun")))
        assertEquals(1, controller.state.value.connections.size)
        assertEquals("new.invalid", controller.state.value.connections.first().destination)
        assertEquals(TunIdentity("new"), controller.state.value.connections.first().identity)
    }

    @Test fun `clearing closed history keeps live flows and prevents later poll from restoring cleared rows`() = runTest {
        val controller = controller(FakeBackend())
        controller.handle(ExpertIntent.Start)
        val flow = ExpertConnectionObservation(
            "active", destination = "example.invalid", protocol = "tcp", decision = "Direct", active = true,
        )
        val closed = flow.copy(id = "closed", active = false)
        controller.onBackendEvent(ExpertBackendEvent.Observation(flow, TunIdentity("tun")))
        controller.onBackendEvent(ExpertBackendEvent.Observation(closed, TunIdentity("tun")))
        controller.handle(ExpertIntent.ClearConnectionHistory)
        controller.onBackendEvent(
            ExpertBackendEvent.ObservationHistory(0, identity = TunIdentity("tun"), visibleFlowIds = setOf("active", "closed")),
        )
        controller.onBackendEvent(ExpertBackendEvent.Observation(closed, TunIdentity("tun")))
        assertEquals(listOf("active"), controller.state.value.connections.map { it.id })
    }

    private fun TestScope.controller(
        backend: FakeBackend,
        initial: NetworkPolicy = NetworkPolicy(),
        persist: ExpertPolicyPersistence = ExpertPolicyPersistence { _, _ -> },
        probeSchedule: ExpertProbeSchedule? = null,
    ): ExpertRuntimeController = ExpertRuntimeController(
        initial, inventory, RoutePlatform.WINDOWS, backend, persist, backgroundScope,
        clockMs = { testScheduler.currentTime }, initialDraft = initial, maintenanceIntervalMs = null,
        nextProbeIntervalMs = { 3_000 },
        probeSchedule = probeSchedule,
    )

    private fun coldPolicy(
        maxPending: Int = 10,
        firstFlowMs: Long = 30_000,
        startupMs: Long = 45_000,
        idleMs: Long = 900_000,
    ): NetworkPolicy = NetworkPolicy(
        device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Channel("channel")),
        channels = listOf(
            PolicyChannel(
                "channel", "Channel", PolicyScope.Device, PolicyTarget.Profile("a", routeScope = null),
                ExitLifecyclePolicy(true, idleMs, firstFlowMs, startupMs, maxPending),
            ),
        ),
    )

    private class FakeBackend : ExpertRuntimeBackend {
        var actualCapabilities = PolicyControlCapabilities(true, true, true)
        override val capabilities: PolicyControlCapabilities get() = actualCapabilities
        var negotiatedCapabilities: PolicyControlCapabilities? = null
        var negotiateGate: CompletableDeferred<Unit>? = null
        var startAck = ExpertTunnelAck(TunIdentity("tun"), 0)
        var applyIdentity = TunIdentity("tun")
        var applyRevision: Long? = null
        var applyGate: CompletableDeferred<Unit>? = null
        var startGate: CompletableDeferred<Unit>? = null
        var wakeGate: CompletableDeferred<Unit>? = null
        var applyFailure: String? = null
        var applyProblem: Exception? = null
        var starts = 0
        var stopFailure: String? = null
        var stopGate: CompletableDeferred<Unit>? = null
        val stops = mutableListOf<TunIdentity?>()
        val wakes = mutableListOf<ExpertExitKey>()
        val recoveries = mutableListOf<ExpertExitKey>()
        val sleeps = mutableListOf<ExpertExitKey>()
        val probeResults = mutableMapOf<ExpertExitKey, ExpertProbeResult>()
        val probeGates = mutableMapOf<ExpertExitKey, CompletableDeferred<Unit>>()
        val probeTimeouts = mutableListOf<Long>()
        var cancelledProbeResult: ExpertProbeResult? = null

        override suspend fun negotiateCapabilities(): PolicyControlCapabilities {
            negotiateGate?.await()
            return negotiatedCapabilities ?: capabilities
        }

        override suspend fun start(program: PolicyProgram): ExpertTunnelAck {
            starts++
            startGate?.await()
            return startAck
        }

        override suspend fun apply(program: PolicyProgram, expected: TunIdentity): ExpertTunnelAck {
            applyGate?.await()
            applyFailure?.let { error(it) }
            applyProblem?.let { throw it }
            return ExpertTunnelAck(applyIdentity, applyRevision ?: program.revision)
        }

        override suspend fun stop(expected: TunIdentity?) {
            stops += expected
            stopGate?.await()
            stopFailure?.let { error(it) }
        }
        override suspend fun wakeExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) {
            wakes += key
            wakeGate?.await()
        }
        override suspend fun recoverExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) {
            recoveries += key
            wakeGate?.await()
        }
        override suspend fun sleepExit(key: ExpertExitKey, generation: Long) {
            sleeps += key
        }
        override suspend fun probeExit(key: ExpertExitKey, timeoutMs: Long): ExpertProbeResult {
            probeTimeouts += timeoutMs
            try {
                (probeGates[key] ?: probeGates[key.copy(folderId = null)])?.await()
            } catch (cancelled: CancellationException) {
                return cancelledProbeResult ?: throw cancelled
            }
            return probeResults[key] ?: probeResults[key.copy(folderId = null)] ?: ExpertProbeResult(20)
        }
    }
}
