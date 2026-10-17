package app.lernet.desktop.protection

import com.google.common.truth.Truth.assertThat
import com.sun.jna.Memory
import org.junit.Test

class SimpleTunnelGuardTest {
    private val core = "C:\\Users\\me\\AppData\\Roaming\\LerNET\\sing-box.exe"
    private val dhcp = "C:\\Windows\\System32\\svchost.exe"

    @Test
    fun closedStageBlocksEverythingExceptCoreLocalAndLinkKeepalive() {
        val filters = SimpleGuardPlan.filters(core, null, dhcp)
        ProtectionLayer.entries.forEach { layer ->
            val own = filters.filter { it.layer == layer }
            assertThat(own.single { it.action == ProtectionAction.BLOCK }.conditions).isEmpty()
            assertThat(own.none { SimpleGuardPlan.isTunPermit(it) }).isTrue()
            assertThat(own.filter { it.action == ProtectionAction.BLOCK }.single().weight)
                .isLessThan(own.filter { it.action == ProtectionAction.PERMIT }.minOf { it.weight })
            // A local-network permit never lets a DNS question past the VPN.
            own.filter { f -> f.conditions.any { it is ProtectionCondition.RemoteSubnet } }.forEach {
                assertThat(it.conditions).contains(ProtectionCondition.RemotePortNot(53))
            }
        }
        assertThat(filters.all { !it.persistent && it.providerKey.isEmpty() && it.sublayerKey == SimpleGuardPlan.SUBLAYER_KEY }).isTrue()
    }

    @Test
    fun adapterPermitFollowsTheCurrentAdapterOnly() {
        val first = SimpleGuardPlan.filters(core, 11L, dhcp).filter(SimpleGuardPlan::isTunPermit)
        assertThat(first).hasSize(4)
        assertThat(first.map { (it.conditions.single() as ProtectionCondition.Interface).luid }.toSet()).containsExactly(11L)
        val second = SimpleGuardPlan.filters(core, 12L, dhcp).filter(SimpleGuardPlan::isTunPermit)
        assertThat(first.map { it.key }.toSet().intersect(second.map { it.key }.toSet())).isEmpty()
    }

    @Test
    fun restartKeepsBlockAndSwapsAdapterPermit() {
        val session = FakeSession()
        var luid = 11L
        var opens = 0
        val guard = SimpleTunnelGuard({ opens++; session }, { luid }, { dhcp }, sleep = {})
        assertThat(guard.engage(core)).isEqualTo(SimpleGuardStatus.Closed)
        assertThat(session.committed.count { it.action == ProtectionAction.BLOCK }).isEqualTo(4)
        assertThat(guard.permitAdapter()).isEqualTo(SimpleGuardStatus.Active)
        assertThat(session.tunLuids()).containsExactly(11L)

        // Core restarted: the block never went away, the new adapter replaces the old permit.
        luid = 12L
        assertThat(guard.engage(core)).isEqualTo(SimpleGuardStatus.Active)
        assertThat(guard.permitAdapter()).isEqualTo(SimpleGuardStatus.Active)
        assertThat(session.tunLuids()).containsExactly(12L)
        assertThat(session.committed.count { it.action == ProtectionAction.BLOCK }).isEqualTo(4)
        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun stopRemovesEverythingAtOnce() {
        val session = FakeSession()
        val guard = SimpleTunnelGuard({ session }, { 11L }, { dhcp }, sleep = {})
        guard.engage(core)
        guard.permitAdapter()
        guard.release()
        assertThat(session.closed).isTrue()
        assertThat(guard.status.value).isEqualTo(SimpleGuardStatus.Off)
        assertThat(guard.isEngaged()).isFalse()
    }

    @Test
    fun missingAdapterStaysClosedAndSaysSo() {
        val session = FakeSession()
        val guard = SimpleTunnelGuard({ session }, { null }, { dhcp }, sleep = {}, adapterWaitMs = 1_000)
        guard.engage(core)
        val status = guard.permitAdapter()
        assertThat(status).isInstanceOf(SimpleGuardStatus.Unavailable::class.java)
        assertThat(session.committed.count { it.action == ProtectionAction.BLOCK }).isEqualTo(4)
        assertThat(session.tunLuids()).isEmpty()
    }

    @Test
    fun windowsWithoutWfpIsReportedHonestly() {
        val guard = SimpleTunnelGuard({ error("BFE недоступна") }, { 11L }, { dhcp }, sleep = {})
        val status = guard.engage(core)
        assertThat(status).isEqualTo(SimpleGuardStatus.Unavailable("BFE недоступна"))
        assertThat(guard.isEngaged()).isFalse()
    }

    @Test
    fun failedApplyClosesSessionInsteadOfLeavingHalfAPolicy() {
        val session = FakeSession().apply { failOnAdd = true }
        val guard = SimpleTunnelGuard({ session }, { 11L }, { dhcp }, sleep = {})
        assertThat(guard.engage(core)).isInstanceOf(SimpleGuardStatus.Unavailable::class.java)
        assertThat(session.committed).isEmpty()
        assertThat(session.closed).isTrue()
    }

    @Test
    fun conditionLayoutsMatchWindowsHeaders() {
        fun encode(condition: ProtectionCondition): Memory = Memory(WfpAbi.CONDITION_SIZE).also { value ->
            value.clear()
            WfpAbi.writeCondition(value, condition) { size -> Memory(size).also { it.clear() } }
        }
        encode(ProtectionCondition.Loopback).let {
            assertThat(WfpAbi.readGuid(it)).isEqualTo(WfpAbi.CONDITION_FLAGS)
            assertThat(it.getInt(16)).isEqualTo(6) // FWP_MATCH_FLAGS_ALL_SET
            assertThat(it.getInt(24)).isEqualTo(3) // FWP_UINT32
            assertThat(it.getInt(32)).isEqualTo(1) // FWP_CONDITION_FLAG_IS_LOOPBACK
        }
        encode(ProtectionCondition.RemotePortNot(53)).let {
            assertThat(WfpAbi.readGuid(it)).isEqualTo(WfpAbi.CONDITION_REMOTE_PORT)
            assertThat(it.getInt(16)).isEqualTo(10) // FWP_MATCH_NOT_EQUAL
            assertThat(it.getInt(24)).isEqualTo(2) // FWP_UINT16
            assertThat(it.getShort(32).toInt()).isEqualTo(53)
        }
        encode(ProtectionCondition.RemoteSubnet("192.168.0.0", 16)).let {
            assertThat(it.getInt(24)).isEqualTo(0x100) // FWP_V4_ADDR_MASK
            val mask = it.getPointer(32)
            assertThat(mask.getInt(0)).isEqualTo(0xC0A80000.toInt())
            assertThat(mask.getInt(4)).isEqualTo(0xFFFF0000.toInt())
        }
        encode(ProtectionCondition.RemoteSubnet("fe80::", 10)).let {
            assertThat(it.getInt(24)).isEqualTo(0x101) // FWP_V6_ADDR_MASK
            val mask = it.getPointer(32)
            assertThat(mask.getByte(0).toInt() and 0xff).isEqualTo(0xfe)
            assertThat(mask.getByte(1).toInt() and 0xff).isEqualTo(0x80)
            assertThat(mask.getByte(16).toInt()).isEqualTo(10)
        }
        encode(ProtectionCondition.Port(68, local = true)).let {
            assertThat(it.getInt(16)).isEqualTo(0)
            assertThat(it.getInt(24)).isEqualTo(2)
        }
    }

    @Test
    fun dynamicSessionFlagIsSet() {
        assertThat(WfpAbi.dynamicSession().getInt(WfpAbi.SESSION_FLAGS_OFFSET)).isEqualTo(1)
        assertThat(WfpAbi.managementSession().getInt(WfpAbi.SESSION_FLAGS_OFFSET)).isEqualTo(0)
    }

    private class FakeSession : WfpSession {
        val committed = mutableListOf<ProtectionFilter>()
        private var pending: MutableList<ProtectionFilter>? = null
        var failOnAdd = false
        var closed = false
        fun tunLuids() = committed.filter(SimpleGuardPlan::isTunPermit)
            .map { (it.conditions.single() as ProtectionCondition.Interface).luid }.toSet()
        override fun ensureOwnership() = Unit
        override fun filters(): List<ProtectionFilter> = (pending ?: committed).toList()
        override fun add(filter: ProtectionFilter) {
            if (failOnAdd) error("FWP add failed")
            checkNotNull(pending).add(filter)
        }
        override fun delete(key: String) {
            checkNotNull(pending).removeAll { it.key == key }
        }
        override fun begin() {
            pending = committed.toMutableList()
        }
        override fun commit() {
            committed.clear()
            committed.addAll(checkNotNull(pending))
            pending = null
        }
        override fun abort() {
            pending = null
        }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun screenTextIsPlainAndSaysWhenTheInternetIsClosed() {
        val technical = SimpleGuardStatus.Unavailable("FwpmEngineOpen0 0x5")
        assertThat(app.lernet.desktop.guardProblemText(technical)).isEqualTo(app.lernet.desktop.SecurityTexts.GUARD_PROBLEM)
        assertThat(app.lernet.desktop.guardProblemText(technical)).doesNotContain("Fwpm")
        assertThat(app.lernet.desktop.guardProblemText(SimpleGuardStatus.Unavailable("x", networkClosed = true)))
            .isEqualTo(app.lernet.desktop.SecurityTexts.GUARD_NETWORK_CLOSED)
        assertThat(app.lernet.desktop.guardProblemText(SimpleGuardStatus.Active)).isNull()
    }
}
