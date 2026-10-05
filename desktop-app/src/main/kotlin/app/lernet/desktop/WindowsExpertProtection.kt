package app.lernet.desktop

import app.lernet.desktop.protection.JnaWfpSession
import app.lernet.desktop.protection.ProtectionAction
import app.lernet.desktop.protection.ProtectionCondition
import app.lernet.desktop.protection.ProtectionFilter
import app.lernet.desktop.protection.ProtectionGuardian
import app.lernet.desktop.protection.ProtectionLayer
import app.lernet.desktop.protection.ProtectionLease
import app.lernet.desktop.protection.ProtectionPlan
import app.lernet.desktop.protection.ProtectionReport
import app.lernet.desktop.protection.ProtectionState
import app.lernet.desktop.protection.ProtectionTunOwner
import app.lernet.desktop.protection.WfpSession
import app.lernet.desktop.protection.WindowsProtectionService
import app.lernet.desktop.protection.isTunAllowance
import java.nio.file.Path

/** Persistent OS filters are independent of the lifetime of the GUI and traffic core. */
class WindowsExpertProtection internal constructor(
    private val openSession: () -> WfpSession,
    private val ensureService: () -> Unit,
    private val isServiceReady: () -> Boolean = { true },
    private val isProtectedCore: (String) -> Boolean = { true },
    private val guardian: ProtectionGuardian,
) {
    constructor() : this(
        { JnaWfpSession.open() }, { WindowsProtectionService.ensureInstalled() },
        { WindowsProtectionService.isReady() }, { WindowsProtectionService.isProtectedCore(it) }, WindowsProtectionService
    )

    companion object {
        fun prepareProtectedCore(source: Path): Result<Path> = runCatching { WindowsProtectionService.prepareProtectedCore(source) }
    }

    /** Failure retains persistent blocks; a failed replacement may close the TUN allowance. */
    @Synchronized
    fun arm(plan: ProtectionPlan, lease: ProtectionLease? = null): Result<ProtectionReport> = runCatching {
        require(plan.errors.isEmpty()) { plan.errors.joinToString("\n") }
        require(plan.filters.isNotEmpty()) { "В плане нет правил защиты Windows" }
        val corePaths = plan.filters.filter { it.action == ProtectionAction.PERMIT && it.conditions.size == 1 }
            .flatMap { it.conditions }.filterIsInstance<ProtectionCondition.Application>().map { it.path }.distinct()
        require(corePaths.size == 1 && isProtectedCore(corePaths.single())) {
            "Для защиты Windows ядро LerNET должно быть запущено из защищённой папки Program Files"
        }
        val tunFilters = plan.filters.filter { it.isTunAllowance }
        require(tunFilters.isEmpty() || lease != null) { "Разрешение TUN требует точной идентичности процесса ядра" }
        ensureService()
        guardian.revoke()
        openSession().use { session ->
            session.transaction {
                session.ensureOwnership()
                owned(session.filters()).forEach { session.delete(it.key) }
                plan.filters.filter { it.persistent }.forEach(session::add)
            }
            if (tunFilters.isNotEmpty()) guardian.arm(tunFilters, checkNotNull(lease))
            report(plan, owned(session.filters()))
        }.also { check(it.state == ProtectionState.ACTIVE) { it.description } }
    }

    /** Service creates and owns the adapter; native must open this exact identity. */
    @Synchronized
    fun prepareOwnedTun(corePath: String, tunName: String, lease: ProtectionLease): Result<ProtectionTunOwner> = runCatching {
        require(isProtectedCore(corePath)) { "Ядро должно быть в защищённой папке Program Files" }
        check(isServiceReady()) { "Служба защиты Windows не готова" }
        guardian.prepare(corePath, tunName, lease)
    }

    /** Revoke before native stop; creator is released after native cleanup or process exit. */
    @Synchronized
    fun revokeOwnedTun(): Result<Unit> = runCatching { guardian.revoke() }

    /** Reading the firewall does not enable protection and does not repair or remove filters. */
    @Synchronized
    fun inspect(expected: ProtectionPlan? = null): Result<ProtectionReport> = runCatching {
        openSession().use { report(expected, owned(it.filters())) }
    }

    /** Explicit user stop/recovery only. This is deliberately not a close/finally/shutdown action. */
    @Synchronized
    fun recover(): Result<ProtectionReport> = runCatching {
        if (isServiceReady()) guardian.releaseStopped()
        openSession().use { session ->
            session.transaction { owned(session.filters()).forEach { session.delete(it.key) } }
            report(null, owned(session.filters()))
        }.also { check(it.state == ProtectionState.OFF) { "Не все фильтры LerNET удалось удалить" } }
    }

    /** Explicit uninstall only. Recovery must succeed before the associated service is removed. */
    fun uninstall(): Result<Unit> = recover().mapCatching { WindowsProtectionService.uninstallOwned() }

    private fun owned(filters: List<ProtectionFilter>): List<ProtectionFilter> = filters.filter {
        it.providerKey == ProtectionPlan.PROVIDER_KEY && it.sublayerKey == ProtectionPlan.SUBLAYER_KEY
    }

    private fun report(plan: ProtectionPlan?, filters: List<ProtectionFilter>): ProtectionReport {
        val serviceKeys = if (filters.any { !it.persistent }) guardian.activeKeys() else emptySet()
        val state = when {
            filters.isEmpty() -> ProtectionState.OFF
            filters.any { it.disabled } -> ProtectionState.DISABLED
            filters.any { !it.compatible || (!it.persistent && !it.isTunAllowance) } -> ProtectionState.INCOMPLETE
            filters.filter { !it.persistent }.any { it.key.lowercase() !in serviceKeys } -> ProtectionState.INCOMPLETE
            ProtectionLayer.entries.any { layer -> filters.none { it.layer == layer && it.action == ProtectionAction.BLOCK } } ->
                ProtectionState.INCOMPLETE
            !isServiceReady() -> ProtectionState.INCOMPLETE
            plan != null &&
                filters.map { it.key to Triple(it.layer, it.action, it.persistent) }.toMap() !=
                plan.filters.map { it.key to Triple(it.layer, it.action, it.persistent) }.toMap() -> ProtectionState.INCOMPLETE
            else -> ProtectionState.ACTIVE
        }
        return ProtectionReport(state, filters.size, plan?.scope, plan?.limitations.orEmpty())
    }
}
