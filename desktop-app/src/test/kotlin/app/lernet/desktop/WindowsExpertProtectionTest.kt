package app.lernet.desktop

import app.lernet.desktop.protection.ProtectedBundleIdentity
import app.lernet.desktop.protection.ProtectionAction
import app.lernet.desktop.protection.ProtectionCondition
import app.lernet.desktop.protection.ProtectionFilter
import app.lernet.desktop.protection.ProtectionGuardian
import app.lernet.desktop.protection.ProtectionIpc
import app.lernet.desktop.protection.ProtectionLayer
import app.lernet.desktop.protection.ProtectionLease
import app.lernet.desktop.protection.ProtectionPlan
import app.lernet.desktop.protection.ProtectionRequest
import app.lernet.desktop.protection.ProtectionScope
import app.lernet.desktop.protection.ProtectionState
import app.lernet.desktop.protection.ProtectionTunOwner
import app.lernet.desktop.protection.ServiceBinaryPolicy
import app.lernet.desktop.protection.ServiceFilePolicy
import app.lernet.desktop.protection.WfpAbi
import app.lernet.desktop.protection.WfpSession
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test

/** No WFP/SCM API, filter engine, service, adapter or application is opened by these tests. */
class WindowsExpertProtectionTest {
    private val request = ProtectionRequest(
        ProtectionScope.DEVICE, "C:\\LerNET\\runtime\\lernet-core.exe", tunLuid = 42,
        ipc = listOf(ProtectionIpc("C:\\LerNET\\LerNET.exe", 49876)),
        dhcpServicePath = "C:\\Windows\\System32\\svchost.exe", acceptedDeviceCoverage = true,
    )
    private val plan get() = ProtectionPlan.build(request)

    @Test fun desktopManagementSessionHasBoundedContentionAndStaticLifetime() {
        val session = WfpAbi.managementSession()
        assertThat(session.size()).isEqualTo(72L)
        assertThat(session.getInt(36)).isEqualTo(2_000)
        assertThat(session.getInt(32)).isEqualTo(0) // Not FWPM_SESSION_FLAG_DYNAMIC.
        // GUID, annotation pointers, padding and all BFE-owned output fields stay zero.
        val bytes = session.getByteArray(0, 72)
        assertThat(bytes.filterIndexed { index, _ -> index !in 36..39 }.all { it == 0.toByte() }).isTrue()
    }

    @Test fun deviceLockdownRequiresAnExplicitScopeChoice() {
        assertThat(ProtectionPlan.build(request.copy(acceptedDeviceCoverage = false)).errors).isNotEmpty()
        assertThat(ProtectionPlan.build(request.copy(acceptedDeviceCoverage = false)).filters).isEmpty()
    }

    @Test fun genericJavaProcessIsNeverTheTrafficCore() {
        assertThat(ProtectionPlan.build(request.copy(corePath = "C:\\jdk\\java.exe")).errors).isNotEmpty()
        assertThat(ProtectionPlan.build(request.copy(corePath = "lernet-core.exe")).errors).isNotEmpty()
        assertThat(ProtectionPlan.build(request.copy(corePath = "\\\\server\\share\\lernet-core.exe")).errors).isNotEmpty()
    }

    @Test fun pathsContainingDotSegmentsAreRejected() {
        assertThat(ProtectionPlan.build(request.copy(corePath = "C:\\LerNET\\..\\lernet-core.exe")).errors).isNotEmpty()
    }

    @Test fun zeroIsNotATunIdentity() {
        assertThat(ProtectionPlan.build(request.copy(tunLuid = 0)).errors).isNotEmpty()
    }

    @Test fun preTunStageHasNoPermissionForOtherAdapters() {
        val preTun = ProtectionPlan.build(request.copy(tunLuid = null))
        assertThat(preTun.errors).isEmpty()
        assertThat(preTun.filters.flatMap { it.conditions }.filterIsInstance<ProtectionCondition.Interface>()).isEmpty()
        assertThat(preTun.filters.count { it.action == ProtectionAction.BLOCK && it.conditions.isEmpty() }).isEqualTo(4)
    }

    @Test fun bothAddressFamiliesAndBothAleDirectionsAreCovered() {
        assertThat(plan.errors).isEmpty()
        assertThat(plan.filters.map { it.layer }.toSet()).containsExactlyElementsIn(ProtectionLayer.entries)
        ProtectionLayer.entries.forEach { layer ->
            val rules = plan.filters.filter { it.layer == layer }
            val block = rules.single { it.action == ProtectionAction.BLOCK }
            assertThat(block.conditions).isEmpty()
            val tun = rules.single { it.conditions.any { condition -> condition is ProtectionCondition.Interface } }
            assertThat(tun.weight).isGreaterThan(block.weight)
            assertThat(tun.conditions).containsExactly(ProtectionCondition.Interface(42, layer.outbound))
        }
    }

    @Test fun onlyOwnCoreGetsAnUnrestrictedApplicationPermission() {
        plan.filters.filter { it.action == ProtectionAction.PERMIT && it.conditions.size == 1 }
            .flatMap { it.conditions }.filterIsInstance<ProtectionCondition.Application>().forEach {
                assertThat(it.path).isEqualTo(request.corePath)
            }
    }

    @Test fun guiPermissionIsConfinedToItsPrivateLoopbackPort() {
        val rules = plan.filters.filter { it.conditions.contains(ProtectionCondition.Application("C:\\LerNET\\LerNET.exe")) }
        assertThat(rules).hasSize(4)
        rules.forEach { rule ->
            assertThat(rule.conditions).contains(ProtectionCondition.Protocol(6))
            assertThat(rule.conditions).contains(ProtectionCondition.Port(49876, local = !rule.layer.outbound))
            assertThat(rule.conditions).contains(ProtectionCondition.Address(if (rule.layer.ipv6) "::1" else "127.0.0.1"))
        }
    }

    @Test fun dhcpExemptionDoesNotPermitSystemDns() {
        val rules = plan.filters.filter { it.conditions.contains(ProtectionCondition.Application("C:\\Windows\\System32\\svchost.exe")) }
        assertThat(rules).hasSize(4)
        rules.forEach { rule ->
            assertThat(rule.conditions).contains(ProtectionCondition.Protocol(17))
            assertThat(rule.conditions).contains(ProtectionCondition.Port(if (rule.layer.ipv6) 546 else 68, true))
            assertThat(rule.conditions).contains(ProtectionCondition.Port(if (rule.layer.ipv6) 547 else 67, false))
            assertThat(rule.conditions.filterIsInstance<ProtectionCondition.Port>().any { it.number == 53 }).isFalse()
        }
    }

    @Test fun ipv6DiscoveryExceptionNeverBecomesGenericIcmpPermission() {
        val rules = plan.filters.filter { it.conditions.contains(ProtectionCondition.Protocol(58)) }
        assertThat(rules).hasSize(8)
        rules.forEach { rule ->
            assertThat(rule.layer.ipv6).isTrue()
            assertThat(rule.conditions.filterIsInstance<ProtectionCondition.Port>().single { it.local }.number).isIn(133..136)
            assertThat(rule.conditions).contains(ProtectionCondition.Port(0, false))
        }
    }

    @Test fun duplicateIpcEntriesDoNotCreateDuplicateNativeFilterKeys() {
        val duplicate = ProtectionPlan.build(request.copy(ipc = request.ipc + request.ipc))
        assertThat(duplicate.filters.map { it.key }.distinct()).hasSize(duplicate.filters.size)
        assertThat(duplicate.filters).hasSize(plan.filters.size)
    }

    @Test fun selectiveCoverageRequiresAFullExePathAndRefusesDomains() {
        val selective = request.copy(scope = ProtectionScope.APPLICATIONS, protectedApplicationPaths = listOf("chrome.exe"))
        assertThat(ProtectionPlan.build(selective).errors).isNotEmpty()
        val valid = selective.copy(protectedApplicationPaths = listOf("C:\\Apps\\Chrome\\chrome.exe"))
        assertThat(ProtectionPlan.build(valid).errors).isEmpty()
        assertThat(ProtectionPlan.build(valid.copy(unsupportedConditions = listOf("domain:example.invalid"))).errors).isNotEmpty()
        assertThat(ProtectionPlan.build(valid).limitations.joinToString()).contains("DNS")
    }

    @Test fun protectedExePathsAreCaseInsensitiveAndNeverSelectTheCore() {
        val selective = request.copy(
            scope = ProtectionScope.APPLICATIONS,
            protectedApplicationPaths = listOf("C:\\Apps\\chrome.exe", "c:\\apps\\CHROME.EXE")
        )
        assertThat(ProtectionPlan.build(selective).filters.count { it.action == ProtectionAction.BLOCK }).isEqualTo(4)
        assertThat(ProtectionPlan.build(selective.copy(protectedApplicationPaths = listOf(request.corePath))).errors).isNotEmpty()
    }

    @Test fun serviceFailureNeverOpensOrWeakensTheExistingFirewall() {
        val fake = FakeSession(mutableListOf(plan.filters.first()))
        var opened = false
        val protection = testProtection({
            opened = true
            fake
        }, { error("SCM denied") })
        assertThat(protection.arm(plan, ProtectionLease(123, 456)).isFailure).isTrue()
        assertThat(opened).isFalse()
        assertThat(fake.committed).hasSize(1)
    }

    @Test fun userWritableCoreIsRejectedBeforeServiceOrFirewallMutation() {
        var opened = false
        var serviceCalled = false
        val protection =
            testProtection({
                opened = true
                FakeSession(mutableListOf())
            }, { serviceCalled = true }, { true }, { false })
        assertThat(protection.arm(plan, ProtectionLease(123, 456)).isFailure).isTrue()
        assertThat(opened).isFalse()
        assertThat(serviceCalled).isFalse()
    }

    @Test fun failedReplacementPreservesPersistentBlocksAndRevokesOldTun() {
        val previous = plan.filters.toMutableList()
        val fake = FakeSession(previous.toMutableList())
        fake.failOnAdd = true
        val protection = testProtection({ fake }, {})
        assertThat(protection.arm(ProtectionPlan.build(request.copy(tunLuid = 99)), ProtectionLease(123, 456)).isFailure).isTrue()
        assertThat(fake.committed).containsExactlyElementsIn(previous.filter { it.persistent })
        assertThat(fake.aborts).isEqualTo(1)
    }

    @Test fun successfulReplacementIsOneTransactionAndClosingNeverRemovesFilters() {
        val fake = FakeSession(mutableListOf())
        val protection = testProtection({ fake }, {})
        val report = protection.arm(plan, ProtectionLease(123, 456)).getOrThrow()
        assertThat(report.state).isEqualTo(ProtectionState.ACTIVE)
        assertThat(fake.commits).isEqualTo(1)
        assertThat(fake.closed).isTrue()
        assertThat(fake.committed).containsExactlyElementsIn(plan.filters)
    }

    @Test fun explicitRecoveryRequiresBothOurProviderAndOurSublayer() {
        val ours = plan.filters.first()
        val foreignProvider = ours.copy(key = UUID.randomUUID().toString(), providerKey = UUID.randomUUID().toString())
        val foreignSublayer = ours.copy(key = UUID.randomUUID().toString(), sublayerKey = UUID.randomUUID().toString())
        val fake = FakeSession(mutableListOf(ours, foreignProvider, foreignSublayer))
        val report = testProtection({ fake }, {}).recover().getOrThrow()
        assertThat(report.state).isEqualTo(ProtectionState.OFF)
        assertThat(fake.committed).containsExactly(foreignProvider, foreignSublayer)
    }

    @Test fun disabledFiltersAndMissingServiceAreNotReportedAsProtection() {
        val disabled = FakeSession(plan.filters.map { it.copy(disabled = true) }.toMutableList())
        assertThat(testProtection({ disabled }, {}).inspect(plan).getOrThrow().state).isEqualTo(ProtectionState.DISABLED)
        val active = FakeSession(plan.filters.toMutableList())
        assertThat(
            testProtection({
                active
            }, {}, { false }).inspect(plan).getOrThrow().state
        ).isEqualTo(ProtectionState.INCOMPLETE)
    }

    @Test fun missingFilterDoesNotPassExpectedRevisionVerification() {
        val fake = FakeSession(plan.filters.dropLast(1).toMutableList())
        assertThat(testProtection({ fake }, {}).inspect(plan).getOrThrow().state).isEqualTo(ProtectionState.INCOMPLETE)
    }

    @Test fun partialOrNonpersistentFiltersNeverPassAnUnscopedStartupInspection() {
        val partial = FakeSession(plan.filters.filter { it.action == ProtectionAction.PERMIT }.toMutableList())
        assertThat(testProtection({ partial }, {}).inspect().getOrThrow().state).isEqualTo(ProtectionState.INCOMPLETE)
        val transient = FakeSession(plan.filters.map { it.copy(persistent = false) }.toMutableList())
        assertThat(testProtection({ transient }, {}).inspect().getOrThrow().state).isEqualTo(ProtectionState.INCOMPLETE)
        val unknown = FakeSession(plan.filters.map { it.copy(compatible = false) }.toMutableList())
        assertThat(testProtection({ unknown }, {}).inspect().getOrThrow().state).isEqualTo(ProtectionState.INCOMPLETE)
    }

    @Test fun contentAddressedServicePathCannotAdoptAnotherApplicationOrArguments() {
        val root = "C:\\Program Files\\LerNETProtection"
        val owned = "\"$root\\${"a".repeat(64)}\\lernet-protection-service.exe\""
        assertThat(ServiceBinaryPolicy.isOwned(owned, root)).isTrue()
        listOf(
            owned + " --admin", owned.drop(1).dropLast(1), owned.replace("LerNETProtection", "Other"),
            owned.replace("lernet-protection-service.exe", "cmd.exe"), owned.replace("a".repeat(64), "..")
        )
            .forEach { assertThat(ServiceBinaryPolicy.isOwned(it, root)).isFalse() }
    }

    @Test fun nativeGuidBytesUseWindowsMixedEndianLayoutAndRoundTrip() {
        val guid = "c38d57d1-05a7-4c33-904f-7fbceee60e82"
        val memory = WfpAbi.guid(guid)
        assertThat(memory.getByteArray(0, 4)).isEqualTo(byteArrayOf(0xd1.toByte(), 0x57, 0x8d.toByte(), 0xc3.toByte()))
        assertThat(WfpAbi.readGuid(memory)).isEqualTo(guid)
        assertThat(WfpAbi.ipv4("127.0.0.1")).isEqualTo(0x7f000001)
    }

    @Test fun onlySingleVerifiedTunAllowsServiceLifetime() {
        val tunFilters = plan.filters.filter { it.conditions.singleOrNull() is ProtectionCondition.Interface }
        assertThat(tunFilters).hasSize(4)
        assertThat(tunFilters.all { !it.persistent }).isTrue()
        assertThat(plan.filters.filter { it !in tunFilters }.all { it.persistent }).isTrue()
        val wrongType = plan.filters.map { if (it in tunFilters) it.copy(conditions = emptyList()) else it }
        assertThat(
            testProtection({
                FakeSession(wrongType.toMutableList())
            }, {}).inspect(plan).getOrThrow().state
        ).isEqualTo(ProtectionState.INCOMPLETE)
        val wrongDirection = plan.filters.map {
            if (it in
                tunFilters
            ) {
                it.copy(conditions = listOf(ProtectionCondition.Interface(42, !it.layer.outbound)))
            } else {
                it
            }
        }
        assertThat(
            testProtection({
                FakeSession(wrongDirection.toMutableList())
            }, {}).inspect(plan).getOrThrow().state
        ).isEqualTo(ProtectionState.INCOMPLETE)
        val restart = plan.filters.filter { it.persistent }
        assertThat(
            testProtection({
                FakeSession(restart.toMutableList())
            }, {}).inspect(plan).getOrThrow().state
        ).isEqualTo(ProtectionState.INCOMPLETE)
        assertThat(
            testProtection({
                FakeSession(restart.toMutableList())
            }, {}).inspect().getOrThrow().state
        ).isEqualTo(ProtectionState.ACTIVE)
    }

    @Test fun dependencyOnlyUpdatesUseANewImmutableCoreDirectory() {
        val dependencies = mapOf("wintun.dll" to "b".repeat(64), "libcronet.dll" to "c".repeat(64))
        val first = ProtectedBundleIdentity.digest("a".repeat(64), dependencies)
        assertThat(ProtectedBundleIdentity.digest("a".repeat(64), dependencies.toSortedMap())).isEqualTo(first)
        assertThat(ProtectedBundleIdentity.digest("a".repeat(64), dependencies + ("wintun.dll" to "d".repeat(64)))).isNotEqualTo(first)
        assertThat(ProtectedBundleIdentity.digest("e".repeat(64), dependencies)).isNotEqualTo(first)
    }

    @Test fun aStaticTunFilterWithoutServiceLeaseIsNeverReportedAsProtected() {
        val fake = FakeSession(plan.filters.toMutableList())
        val guardian = object : ProtectionGuardian {
            override fun prepare(corePath: String, tunName: String, lease: ProtectionLease): ProtectionTunOwner = error("Unused")
            override fun arm(filters: List<ProtectionFilter>, lease: ProtectionLease) = error("Unused")
            override fun revoke() = Unit
            override fun releaseStopped() = Unit
            override fun activeKeys(): Set<String> = emptySet()
        }
        val protection = WindowsExpertProtection({ fake }, {}, guardian = guardian)
        assertThat(protection.inspect(plan).getOrThrow().state).isEqualTo(ProtectionState.INCOMPLETE)
    }

    @Test fun liveTunRequiresPinnedProcessIdentityBeforeAnyMutation() {
        val fake = FakeSession(mutableListOf())
        var service = false
        val protection = testProtection({ fake }, { service = true })
        assertThat(protection.arm(plan).isFailure).isTrue()
        assertThat(service).isFalse()
        assertThat(fake.committed).isEmpty()
    }

    @Test fun trustedCoreAclHasNoUserWriteOwnerOrInheritedPermissions() {
        val protected = "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FRFX;;;BU)"
        assertThat(ServiceFilePolicy.isProtectedDescriptor(protected)).isTrue()
        assertThat(ServiceFilePolicy.isProtectedDescriptor(protected.replace("FRFX", "0x1200a9"))).isTrue()
        listOf(
            protected.replace("O:BA", "O:BU"), protected.replace("D:P", "D:"), protected.replace("FRFX", "FA"),
            protected + "(A;;FA;;;WD)", protected.replace("(A;;FA;;;BA)", "(A;OI;FA;;;BA)")
        )
            .forEach { assertThat(ServiceFilePolicy.isProtectedDescriptor(it)).isFalse() }
    }

    @Test fun exemptCoreFilesCannotBeExecutedByAnOrdinaryUser() {
        val directory = "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FRFX;;;BU)"
        val core = directory.replace("FRFX", "FR")
        assertThat(ServiceFilePolicy.isProtectedDescriptor(core, allowUserExecute = false)).isTrue()
        assertThat(ServiceFilePolicy.isProtectedDescriptor(core.replace("FR", "0x120089"), allowUserExecute = false)).isTrue()
        assertThat(ServiceFilePolicy.isProtectedDescriptor(directory, allowUserExecute = false)).isFalse()
        assertThat(ServiceFilePolicy.isProtectedDescriptor(core)).isFalse()
    }

    @Test fun explicitRecoveryCannotRemoveBaseBlocksWhileNativeStillOwnsTheCreator() {
        val persistent = plan.filters.filter { it.persistent }
        val fake = FakeSession(persistent.toMutableList())
        val protection = testProtection({ fake }, {}, releaseStopped = { error("Pinned native is still alive") })
        assertThat(protection.recover().isFailure).isTrue()
        assertThat(fake.committed).isEqualTo(persistent)
        assertThat(fake.commits).isEqualTo(0)
    }

    @Test fun explicitRecoveryConfirmsDeadCreatorCleanupBeforeDeletingBaseBlocks() {
        val fake = FakeSession(plan.filters.filter { it.persistent }.toMutableList())
        var released = false
        fake.beforeDelete = { check(released) { "Base block removed before creator cleanup" } }
        val protection = testProtection({ fake }, {}, releaseStopped = { released = true })
        assertThat(protection.recover().getOrThrow().state).isEqualTo(ProtectionState.OFF)
        assertThat(released).isTrue()
        assertThat(fake.committed).isEmpty()
    }

    private fun testProtection(
        openSession: () -> WfpSession,
        ensureService: () -> Unit,
        isServiceReady: () -> Boolean = { true },
        isProtectedCore: (String) -> Boolean = { true },
        releaseStopped: (() -> Unit)? = null,
    ): WindowsExpertProtection {
        val guardian = object : ProtectionGuardian {
            private fun store() = (openSession() as FakeSession).committed
            override fun prepare(
                corePath: String,
                tunName: String,
                lease: ProtectionLease
            ): ProtectionTunOwner = error("Unused fake prepare")
            override fun arm(filters: List<ProtectionFilter>, lease: ProtectionLease) {
                store().addAll(filters)
            }
            override fun revoke() {
                store().removeAll {
                    !it.persistent &&
                        it.providerKey == ProtectionPlan.PROVIDER_KEY &&
                        it.sublayerKey == ProtectionPlan.SUBLAYER_KEY
                }
            }
            override fun releaseStopped() {
                releaseStopped?.invoke() ?: revoke()
            }
            override fun activeKeys(): Set<String> = store().filter { !it.persistent }.map { it.key }.toSet()
        }
        return WindowsExpertProtection(openSession, ensureService, isServiceReady, isProtectedCore, guardian)
    }

    private class FakeSession(val committed: MutableList<ProtectionFilter>) : WfpSession {
        private var pending: MutableList<ProtectionFilter>? = null
        var commits = 0
        var aborts = 0
        var closed = false
        var failOnAdd = false
        var beforeDelete: () -> Unit = {}
        override fun ensureOwnership() = Unit
        override fun filters(): List<ProtectionFilter> = (pending ?: committed).toList()
        override fun add(filter: ProtectionFilter) {
            if (failOnAdd) error("FWP add failed")
            checkNotNull(pending).add(filter)
        }
        override fun delete(key: String) {
            beforeDelete()
            checkNotNull(pending).removeAll { it.key == key }
        }
        override fun begin() {
            check(pending == null)
            pending = committed.toMutableList()
        }
        override fun commit() {
            committed.clear()
            committed.addAll(checkNotNull(pending))
            pending = null
            commits++
        }
        override fun abort() {
            pending = null
            aborts++
        }
        override fun close() {
            closed = true
        }
    }
}
