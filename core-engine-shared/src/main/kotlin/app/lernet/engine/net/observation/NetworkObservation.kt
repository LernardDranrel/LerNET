package app.lernet.engine.net.observation

import kotlinx.serialization.Serializable

@Serializable
enum class SourceState { AVAILABLE, EMPTY, ACCESS_DENIED, UNSUPPORTED, ERROR, TIMEOUT }

@Serializable
data class EvidenceRow(val id: String, val title: String, val fields: Map<String, String> = emptyMap())

@Serializable
data class ObservationSource(
    val id: String,
    val title: String,
    val explanation: String,
    val state: SourceState = SourceState.AVAILABLE,
    val rows: List<EvidenceRow> = emptyList(),
    val detail: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    val complete: Boolean = true,
)

@Serializable
data class ObservedAdapter(
    val id: String, val index: Int, val name: String, val description: String = "",
    val up: Boolean, val virtual: Boolean = false, val addresses: List<String> = emptyList(),
    val dns: List<String> = emptyList(), val metric: Int = 0, val mtu: Int? = null,
)

@Serializable
data class ObservedRoute(
    val prefix: String, val nextHop: String, val adapterId: String, val interfaceIndex: Int,
    val metric: Int, val interfaceMetric: Int = 0, val store: String = "ActiveStore",
    val compartment: String = "",
    val metricsKnown: Boolean = true,
)

@Serializable
data class ObservedListener(val address: String, val port: Int, val pid: Long, val process: String = "")

@Serializable
enum class FindingKind { FACT, POTENTIAL_CONFLICT, INSUFFICIENT_DATA }

@Serializable
data class NetworkFinding(
    val code: String, val title: String, val explanation: String, val kind: FindingKind,
    val sourceIds: List<String>, val evidence: List<String> = emptyList(),
    val reason: String = "", val impact: String = "", val nextSteps: List<String> = emptyList(),
    val relatedItems: List<EvidenceRow> = emptyList(),
)

@Serializable
data class NetworkSnapshot(
    val schemaVersion: Int = 1,
    val id: String = "snapshot-${System.currentTimeMillis()}",
    val platform: String, val startedAt: Long, val finishedAt: Long = startedAt,
    val elevated: Boolean = false, val userContext: String = "",
    val adapters: List<ObservedAdapter> = emptyList(),
    val routes: List<ObservedRoute> = emptyList(),
    val listeners: List<ObservedListener> = emptyList(),
    val sources: List<ObservationSource> = emptyList(),
    val findings: List<NetworkFinding> = emptyList(),
    val baseline: NetworkSnapshot? = null,
    val changes: List<SnapshotChange> = emptyList(),
    val diagnosticLog: List<String> = emptyList(),
    val diagnosticLogCapturedAt: Long? = null,
)

@Serializable
data class SnapshotChange(val sourceId: String, val title: String, val before: String, val after: String)

object NetworkObservationAnalysis {
    fun analyze(snapshot: NetworkSnapshot, ownPids: Set<Long> = emptySet()): List<NetworkFinding> = buildList {
        addAll(NetworkDependencies.localDnsFindings(snapshot))
        val occupied = snapshot.listeners.filter {
            it.port == 2080 && it.pid !in ownPids && it.address in setOf("0.0.0.0", "127.0.0.1", "::", "::1", "::ffff:127.0.0.1")
        }
        if (occupied.isNotEmpty()) add(NetworkFinding("proxy-port-occupied", "Порт прокси занят",
            "LerNET использует порт 2080. Другой процесс уже слушает его; адрес и семейство IP нужно сверить перед подключением.",
            FindingKind.POTENTIAL_CONFLICT, listOf("listeners"), occupied.map { "${it.address}:${it.port} · ${it.process.ifBlank { "PID ${it.pid}" }}" }))
        val listenerSource = snapshot.sources.firstOrNull { it.id == "listeners" }
        if (listenerSource != null && listenerSource.hasReadableContents()) {
            val proxyRows = snapshot.sources.filter { it.id == "user-proxy" && it.state == SourceState.AVAILABLE }.flatMap { it.rows }
            val missingProxyEndpoints = proxyRows.filter { it.fields["ProxyEnable"] in setOf("1", "true", "True") }
                .flatMap { it.fields["ProxyServer"].orEmpty().split(';') }
                .mapNotNull { configuredLoopbackProxy(it.substringAfter('=')) }
                .distinct()
                .filter { (host, port) ->
                    snapshot.listeners.none { listener ->
                        listener.port == port && when (host) {
                            "::1" -> listener.address in setOf("::1", "::")
                            // An IPv6 wildcard may be dual-stack; the snapshot does not expose IPV6_V6ONLY.
                            "127.0.0.1" -> listener.address in setOf("127.0.0.1", "0.0.0.0", "::", "::ffff:127.0.0.1")
                            else -> listener.address in setOf("127.0.0.1", "::1", "0.0.0.0", "::")
                        }
                    }
                }
            if (missingProxyEndpoints.isNotEmpty()) add(NetworkFinding("loopback-proxy-no-listener", "Локальный прокси указан, но не найден",
                "Пользовательские настройки направляют запросы на этот компьютер. В снимке не найден слушающий TCP-порт; программа могла завершиться или ещё не запуститься. Снимок не атомарный, а прокси применяют не все приложения.",
                FindingKind.POTENTIAL_CONFLICT, listOf("user-proxy", "listeners"), missingProxyEndpoints.map { "${it.first}:${it.second}" }))
        }
        val blockedEvents = snapshot.sources.filter { it.id in setOf("events", "trace-events") }.flatMap { source ->
            source.rows.filter { row -> row.fields["Event ID"] == "5157" && row.fields["Источник"] == "Microsoft-Windows-Security-Auditing" }.map { source.id to it }
        }
        if (blockedEvents.isNotEmpty()) add(NetworkFinding("windows-block-event", "Windows записала блокировку соединения",
            "В журнале есть событие WFP 5157. Оно относится к указанному времени и программе, а не обязательно к текущему подключению. ID фильтра может измениться после перезапуска; виновник не назначается по одному номеру.",
            FindingKind.FACT, blockedEvents.map { it.first }.distinct(), blockedEvents.take(8).map { (_, row) ->
                row.fields.filterKeys { it in setOf("Время UTC", "Application", "ProcessId", "DestAddress", "DestPort", "FilterRTID") }.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            }))
        val inactive = snapshot.routes.filter { route -> snapshot.adapters.any {
            it.id == route.adapterId && !it.up && !ObservationGuide.isServiceRoute(route, it)
        } }
        if (inactive.isNotEmpty()) add(NetworkFinding("inactive-route", "Есть маршруты через отключённый адаптер",
            "В таблице есть общие или целевые пути через интерфейс, который сейчас отключён. Наличие записи не доказывает, что система выбирает этот путь.",
            FindingKind.POTENTIAL_CONFLICT, listOf("routes", "adapters"), inactive.map { route ->
                val adapter = snapshot.adapters.first { it.id == route.adapterId }
                "${adapter.name} · отключён · ${route.prefix} → ${route.nextHop}"
            }))
        val splitDefaults = snapshot.routes.filter {
            it.store.equals("ActiveStore", ignoreCase = true) &&
                it.prefix in setOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1")
        }
        if (splitDefaults.isNotEmpty()) add(NetworkFinding("split-default", "Найдены маршруты с приоритетом перед шлюзом",
            "В одном сетевом контексте подходящий маршрут /1 имеет приоритет перед /0 независимо от метрики. Так VPN может направлять трафик, оставляя обычный шлюз в таблице. Какой путь выбран для конкретного адреса, нужно проверить отдельно.",
            FindingKind.FACT, listOf("routes"), splitDefaults.map { "${it.prefix} → ${it.nextHop} · интерфейс ${it.interfaceIndex}" + if (it.compartment.isBlank()) "" else " · контекст ${it.compartment}" }))
        snapshot.adapters.filter { it.virtual && it.up }.takeIf { it.isNotEmpty() }?.let { virtual ->
            add(NetworkFinding("virtual-active", "Виртуальные каналы включены",
                "Это могут быть VPN, виртуальные машины или контейнеры. Включённый адаптер сам по себе не означает конфликт или активный VPN.",
                FindingKind.FACT, listOf("adapters"), virtual.map { it.name + " · " + it.description }))
        }
        snapshot.sources.filter { !it.hasReadableContents() }.takeIf { it.isNotEmpty() }?.let { missing ->
            add(NetworkFinding("partial-snapshot", "Некоторые данные недоступны",
                "Выводы относятся только к прочитанным источникам. Пустой журнал или отсутствие прав не подтверждают отсутствие проблем.",
                FindingKind.INSUFFICIENT_DATA, missing.map { it.id }, missing.map { "${it.title}: ${it.availabilityDescription()}" }))
        }
    }.map { ObservationFindingGuide.explain(snapshot, it) }

    private fun configuredLoopbackProxy(value: String): Pair<String, Int>? {
        val match = Regex("^(?:https?://)?(127\\.0\\.0\\.1|localhost|\\[::1\\]):([0-9]{1,5})/?$", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return match.groupValues[1].removeSurrounding("[", "]").lowercase() to port
    }

    fun compare(previous: NetworkSnapshot, current: NetworkSnapshot): List<SnapshotChange> {
        val old = previous.sources.associateBy { it.id }
        return current.sources.flatMap { source ->
            val prior = old[source.id] ?: return@flatMap listOf(SnapshotChange(source.id, source.title, "Источник не был прочитан", source.state.name))
            if (!prior.hasReadableContents() || !source.hasReadableContents()) {
                if (!prior.hasReadableContents() && !source.hasReadableContents() &&
                    prior.state == source.state && prior.complete == source.complete && prior.detail == source.detail) {
                    return@flatMap emptyList()
                }
                return@flatMap listOf(SnapshotChange(source.id, source.title,
                    prior.availabilityDescription(),
                    source.availabilityDescription() + ". Содержимое источника не сравнивалось: в одном из снимков данных недостаточно."))
            }
            val before = prior.rows.associateBy { it.id }
            val after = source.rows.associateBy { it.id }
            val changed = (before.keys + after.keys).sorted().mapNotNull { key ->
                val a = before[key]; val b = after[key]
                if (a == b) null else SnapshotChange(source.id, b?.title ?: a!!.title,
                    a?.fields?.toSortedMap()?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" } ?: "Не было",
                    b?.fields?.toSortedMap()?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" } ?: "Больше нет")
            }
            if (prior.state != source.state) listOf(SnapshotChange(source.id, source.title, prior.state.name, source.state.name)) + changed else changed
        } + old.values.filter { prior -> current.sources.none { it.id == prior.id } }.map {
            SnapshotChange(it.id, it.title, "Источник был прочитан", "Не вошёл в новый снимок")
        }
    }

    private fun ObservationSource.hasReadableContents() = complete && (state == SourceState.AVAILABLE || state == SourceState.EMPTY)

    private fun ObservationSource.availabilityDescription(): String {
        val availability = when (state) {
            SourceState.AVAILABLE -> "Данные прочитаны"
            SourceState.EMPTY -> "Источник прочитан, записей нет"
            SourceState.ACCESS_DENIED -> "Доступ запрещён"
            SourceState.UNSUPPORTED -> "Источник недоступен на этой платформе"
            SourceState.ERROR -> "Не удалось прочитать источник"
            SourceState.TIMEOUT -> "Сбор источника не завершился вовремя"
        }
        return (if (detail.isBlank()) availability else "$availability: $detail") + if (!complete) " · выборка неполная" else ""
    }
}
