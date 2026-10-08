package app.lernet.desktop.expert

import app.lernet.engine.policy.FlowCounters
import app.lernet.engine.policy.FlowTrafficRate
import app.lernet.engine.policy.FlowTrafficSampler

internal const val LONG_LIVED_FLOW_MS = 30_000L
internal const val TRAFFIC_FRESHNESS_MS = 6_000L

internal typealias FlowRate = FlowTrafficRate

/** Sample only confirmed counter snapshots; the UI clock does not create bandwidth evidence. */
internal class FlowRateSampler {
    private val sampler = FlowTrafficSampler()

    fun update(flows: List<ExpertConnection>): Map<String, FlowRate> = sampler.update(
        flows.map {
            FlowCounters(it.id, it.startedAtMs, it.observedAtMs, it.uploadedBytes, it.downloadedBytes)
        }
    )
}

internal fun isLongLivedFlow(flow: ExpertConnection, nowMs: Long): Boolean {
    val started = flow.startedAtMs ?: return false
    return flow.active == true && started > 0 && nowMs >= started && nowMs - started >= LONG_LIVED_FLOW_MS
}

internal fun freshFlowRate(flow: ExpertConnection, rate: FlowRate?, nowMs: Long): FlowRate? {
    if (flow.active != true) return null
    val observed = flow.observedAtMs ?: return null
    return rate?.takeIf { nowMs >= observed && nowMs - observed <= TRAFFIC_FRESHNESS_MS }
}

internal fun flowDuration(flow: ExpertConnection, nowMs: Long): String {
    val started = flow.startedAtMs?.takeIf { it > 0 } ?: return "Длительность неизвестна"
    val end = flow.closedAtMs ?: if (flow.active == true) nowMs else return "Начало известно; время закрытия не передано"
    val seconds = (end - started).coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "$seconds с"
        seconds < 3600 -> "${seconds / 60} мин ${seconds % 60} с"
        else -> "${seconds / 3600} ч ${seconds / 60 % 60} мин"
    }
}

internal fun flowErrorMeaning(code: String): String = when (code) {
    "timeout" -> "За отведённое время ответ не получен"
    "name_not_found" -> "DNS сообщил, что такого имени нет"
    "resolution_failed" -> "Не удалось получить адрес назначения через DNS"
    "network_error" -> "Сетевая операция завершилась ошибкой"
    "system_route_unavailable" -> "Windows не нашла прямой путь к этому адресу вне нашего TUN"
    "system_route_ipv4_unavailable" -> "Вне TUN нет доступного маршрута IPv4 к этому адресу"
    "system_route_ipv6_unavailable" -> "Вне TUN нет доступного маршрута IPv6 к этому адресу; IPv6 через VPN проверяется отдельно"
    "system_route_manager_unavailable" -> "Системный обработчик маршрутов недоступен"
    "system_route_snapshot_failed" -> "Не удалось прочитать актуальную таблицу маршрутов Windows"
    "system_route_bind_failed" -> "Windows не разрешила привязать соединение к выбранному адаптеру"
    "ingress_not_ready" -> "Туннель ещё не подтвердил готовность своего адаптера"
    "ingress_ready_timeout" -> "Туннель не подтвердил готовность маршрутизации за отведённое время"
    "system_route_destination_invalid" -> "Адрес не подходит для выбора системного маршрута"
    "system_route_changed" -> "Системный путь изменился во время подключения"
    "interface_binding_invalid" -> "В настройках выхода некорректно указан адаптер"
    "interface_binding_unavailable" -> "Нужный адаптер сейчас недоступен"
    "interface_binding_identity_changed" -> "Адаптер больше не соответствует сохранённой привязке"
    "interface_binding_owned_ingress" -> "Выход указывает на наш собственный TUN; это создало бы петлю"
    else -> "Ядро сообщило причину: $code"
}

internal fun flowStageMeaning(stage: String): String = when (stage) {
    "dns" -> "Разрешение имени"
    "route" -> "Выбор пути"
    "dial" -> "Установка соединения"
    "transfer" -> "Передача данных"
    else -> "Сетевое соединение"
}
