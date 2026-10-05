package app.lernet.desktop.protection

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

enum class ProtectionScope { APPLICATIONS, DEVICE }

enum class ProtectionState { OFF, ACTIVE, DISABLED, INCOMPLETE }

data class ProtectionReport(
    val state: ProtectionState,
    val filterCount: Int,
    val scope: ProtectionScope?,
    val limitations: List<String> = emptyList(),
) {
    val description: String get() = when (state) {
        ProtectionState.OFF -> "Защита Windows выключена"
        ProtectionState.ACTIVE -> "Windows удерживает трафик при остановке LerNET; фильтров: $filterCount"
        ProtectionState.DISABLED -> "Windows отключила фильтры LerNET. Защита от обхода не подтверждена"
        ProtectionState.INCOMPLETE -> "Набор фильтров Windows отличается от плана. Защита не подтверждена"
    }
}

data class ProtectionIpc(val applicationPath: String, val port: Int)

data class ProtectionRequest(
    val scope: ProtectionScope,
    val corePath: String,
    /** Null intentionally builds the fail-closed stage before the TUN exists. */
    val tunLuid: Long? = null,
    val protectedApplicationPaths: List<String> = emptyList(),
    val ipc: List<ProtectionIpc> = emptyList(),
    val dhcpServicePath: String? = null,
    /** The GUI must show these before the user accepts whole-device coverage. */
    val unsupportedConditions: List<String> = emptyList(),
    val acceptedDeviceCoverage: Boolean = false,
)

data class ProtectionPlan(
    val scope: ProtectionScope,
    val filters: List<ProtectionFilter>,
    val errors: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
) {
    companion object {
        const val PROVIDER_KEY = "2dbb248a-91c0-45b4-96cc-dd0a1b4e2b8a"
        const val SUBLAYER_KEY = "6fa5577e-591a-41aa-8d9c-bb0a16d8e4da"
        const val SERVICE_NAME = "LerNETProtection"

        fun build(request: ProtectionRequest): ProtectionPlan {
            val errors = mutableListOf<String>()
            fun validPath(path: String): Boolean =
                Regex("^[A-Za-z]:\\\\[^\\u0000<>:\"|?*/]+\\.exe$", RegexOption.IGNORE_CASE).matches(path) &&
                    path.split('\\').none { it == ".." || it == "." }
            if (!validPath(request.corePath) || !request.corePath.substringAfterLast('\\').equals("lernet-core.exe", true)) {
                errors += "Защите нужен полный путь к собственному ядру lernet-core.exe"
            }
            if (request.tunLuid == 0L) errors += "Windows не сообщила идентификатор адаптера LerNET"
            if (request.ipc.any { !validPath(it.applicationPath) || it.port !in 1..65535 }) {
                errors += "Неверный путь или порт канала управления"
            }
            if (request.dhcpServicePath?.let { !validPath(it) || !it.substringAfterLast('\\').equals("svchost.exe", true) } == true) {
                errors += "Для служебного DHCP нужен полный путь к Windows svchost.exe"
            }
            val apps = request.protectedApplicationPaths.distinctBy { it.lowercase(Locale.ROOT) }
            if (apps.any { !validPath(it) }) errors += "Защита программы требует полный путь к EXE, одного имени процесса недостаточно"
            if (apps.any { it.equals(request.corePath, true) }) errors += "Ядро LerNET нельзя выбрать как защищаемую программу"
            if (request.scope == ProtectionScope.APPLICATIONS && apps.isEmpty()) errors += "Не выбрана ни одна программа для защиты"
            if (request.scope == ProtectionScope.APPLICATIONS && request.unsupportedConditions.isNotEmpty()) {
                errors += "WFP не подтверждает отдельную защиту доменов или сложных условий; выберите защиту всего устройства"
            }
            if (request.scope == ProtectionScope.DEVICE && !request.acceptedDeviceCoverage) {
                errors += "Подтвердите, что при остановке LerNET доступ в сеть будет закрыт для всего устройства"
            }
            val limits = buildList {
                add(
                    "Фильтры защищают обычные IPv4 и IPv6 соединения Windows. " +
                        "Они не заменяют защиту от администратора или стороннего драйвера."
                )
                add("Разрешён собственный lernet-core.exe: он выпускает трафик по вашей схеме, в том числе разрешённые прямые ветки.")
                add(
                    "Фильтры остаются после закрытия окна и сбоя ядра. " +
                        "Windows возвращает их при запуске BFE, пока служба LerNET настроена на автоматический запуск."
                )
                add(
                    "TUN принадлежит службе: разрешения снимаются до удаления адаптера. " +
                        "После перезапуска BFE старые разрешения TUN не восстанавливаются."
                )
                add(
                    "Ранний этап загрузки Windows и время, когда системная служба фильтрации BFE недоступна, " +
                        "не покрыты защитой; это не загрузочный сетевой драйвер."
                )
                if (request.scope == ProtectionScope.APPLICATIONS) {
                    add(
                        "Защищён только выбранный путь EXE. " +
                            "Общий Windows DNS и дочерние программы требуют отдельной защиты или режима всего устройства."
                    )
                }
                if (request.dhcpServicePath != null) {
                    add("Windows может обновить адрес сети через служебные DHCP порты; обычный DNS не исключён из защиты.")
                }
                if (request.unsupportedConditions.isNotEmpty()) {
                    add("Домены и сложные условия защищаются на уровне ядра; после его сбоя запрет действует на всё устройство.")
                }
                if (request.scope == ProtectionScope.DEVICE) {
                    add("Локальные подключения к localhost тоже ограничены: исключение есть только для управления LerNET.")
                    add(
                        "Защита всего устройства может заблокировать соединение другого VPN с его сервером. " +
                            "Отдельные процессы корпоративного SSTP/IPsec не получают исключение LerNET."
                    )
                    add(
                        "Разрешено служебное обнаружение IPv6 соседей и роутеров; " +
                            "это не разрешает произвольный ICMP, DNS или соединения программ."
                    )
                }
                add("Удаление собственных фильтров выполняется кнопкой восстановления. Правила других VPN и брандмауэров сохраняются.")
            }
            if (errors.isNotEmpty()) return ProtectionPlan(request.scope, emptyList(), errors, limits)
            val filters = mutableListOf<ProtectionFilter>()
            ProtectionLayer.entries.forEach { layer ->
                fun add(id: String, action: ProtectionAction, weight: Long, conditions: List<ProtectionCondition>) {
                    filters += ProtectionFilter(
                        key = UUID.nameUUIDFromBytes(
                            "LerNET protection v1|${layer.name}|$id".toByteArray(StandardCharsets.UTF_8)
                        ).toString(),
                        layer = layer, action = action, weight = weight, conditions = conditions,
                        persistent = conditions.none { it is ProtectionCondition.Interface },
                    )
                }
                // A soft permit within our sublayer skips our block, without overriding another firewall.
                add(
                    "core:${request.corePath.lowercase(Locale.ROOT)}", ProtectionAction.PERMIT, 1000,
                    listOf(ProtectionCondition.Application(request.corePath))
                )
                if (request.tunLuid != null) {
                    add(
                        "tun:${request.tunLuid}", ProtectionAction.PERMIT, 900,
                        listOf(ProtectionCondition.Interface(request.tunLuid, layer.outbound))
                    )
                }
                request.ipc.distinctBy { it.applicationPath.lowercase(Locale.ROOT) to it.port }.forEach { ipc ->
                    add(
                        "ipc:${ipc.applicationPath.lowercase(Locale.ROOT)}:${ipc.port}", ProtectionAction.PERMIT, 800,
                        listOf(
                            ProtectionCondition.Application(ipc.applicationPath), ProtectionCondition.Protocol(6),
                            ProtectionCondition.Address(if (layer.ipv6) "::1" else "127.0.0.1"),
                            ProtectionCondition.Port(ipc.port, local = !layer.outbound),
                        )
                    )
                }
                if (request.scope == ProtectionScope.DEVICE) {
                    request.dhcpServicePath?.let { path ->
                        add(
                            "dhcp", ProtectionAction.PERMIT, 700,
                            listOf(
                                ProtectionCondition.Application(path), ProtectionCondition.Protocol(17),
                                ProtectionCondition.Port(if (layer.ipv6) 546 else 68, local = true),
                                ProtectionCondition.Port(if (layer.ipv6) 547 else 67, local = false),
                            )
                        )
                    }
                    if (layer.ipv6) {
                        // Neighbor/router discovery uses ICMPv6 type/code in the WFP port fields.
                        // Never exempt all ICMPv6, which could carry arbitrary application data.
                        (133..136).forEach { type ->
                            add(
                                "ndp:$type", ProtectionAction.PERMIT, 700,
                                listOf(
                                    ProtectionCondition.Protocol(58), ProtectionCondition.Port(type, local = true),
                                    ProtectionCondition.Port(0, local = false),
                                )
                            )
                        }
                    }
                    add("lockdown", ProtectionAction.BLOCK, 100, emptyList())
                } else {
                    apps.forEach { path ->
                        add(
                            "app:${path.lowercase(Locale.ROOT)}", ProtectionAction.BLOCK, 100,
                            listOf(ProtectionCondition.Application(path))
                        )
                    }
                }
            }
            return ProtectionPlan(request.scope, filters, limitations = limits)
        }
    }
}

enum class ProtectionLayer(val key: String, val ipv6: Boolean, val outbound: Boolean) {
    CONNECT_V4("c38d57d1-05a7-4c33-904f-7fbceee60e82", false, true),
    CONNECT_V6("4a72393b-319f-44bc-84c3-ba54dcb3b6b4", true, true),
    ACCEPT_V4("e1cd9fe7-f4b5-4273-96c0-592e487b8650", false, false),
    ACCEPT_V6("a3b42c97-9f04-4672-b87e-cee9c483257f", true, false),
}

enum class ProtectionAction { PERMIT, BLOCK }

sealed interface ProtectionCondition {
    data class Application(val path: String) : ProtectionCondition
    data class Interface(val luid: Long, val nextHop: Boolean) : ProtectionCondition
    data class Protocol(val number: Int) : ProtectionCondition
    data class Port(val number: Int, val local: Boolean) : ProtectionCondition
    data class Address(val numeric: String) : ProtectionCondition
}

data class ProtectionFilter(
    val key: String,
    val layer: ProtectionLayer,
    val action: ProtectionAction,
    val weight: Long,
    val conditions: List<ProtectionCondition>,
    val providerKey: String = ProtectionPlan.PROVIDER_KEY,
    val sublayerKey: String = ProtectionPlan.SUBLAYER_KEY,
    val disabled: Boolean = false,
    val persistent: Boolean = true,
    val compatible: Boolean = true,
)

/** Only the guardian's verified single live-interface permit may be nonpersistent. */
internal val ProtectionFilter.isTunAllowance: Boolean get() {
    val condition = conditions.singleOrNull() as? ProtectionCondition.Interface ?: return false
    return !persistent && action == ProtectionAction.PERMIT && condition.luid != 0L && condition.nextHop == layer.outbound
}
