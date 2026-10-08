package app.lernet.ui.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.net.RouteInfo
import app.lernet.engine.net.observation.*

/** An injectable read boundary: no requestNetwork, VPN permission or network probes. */
internal fun interface AndroidNetworkReader { fun read(): List<AndroidNetworkRecord> }

internal data class AndroidNetworkRecord(
    val id: String,
    val name: String,
    val transports: List<String>,
    val active: Boolean,
    val internet: Boolean,
    val validated: Boolean,
    val vpn: Boolean,
    val ownerIsSelf: Boolean,
    val addresses: List<String>,
    val dns: List<String>,
    val routes: List<Pair<String, String>>,
    val mtu: Int,
    val proxy: String,
    val privateDns: String,
    val propertiesAvailable: Boolean = true,
    val capabilitiesAvailable: Boolean = true,
    val interfaceIndex: Int = 0,
    val routeTypes: Map<String, Int> = emptyMap(),
)

internal class ConnectivityNetworkReader(context: Context) : AndroidNetworkReader {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    // getAllNetworks remains the platform inventory API for this one-shot, user-refreshed
    // snapshot. It is not polled for monitoring; recheck membership to reject churn.
    @Suppress("DEPRECATION")
    override fun read(): List<AndroidNetworkRecord> {
        val active = manager.activeNetwork
        val networks = manager.allNetworks.toSet()
        val records = networks.map { network ->
            val caps = manager.getNetworkCapabilities(network)
            val links = manager.getLinkProperties(network)
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Мобильная сеть")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) == true) add("Wi-Fi Aware")
                if (Build.VERSION.SDK_INT >= 27 && caps?.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) == true) add("LoWPAN")
                if (Build.VERSION.SDK_INT >= 31 && caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true) add("USB")
                if (Build.VERSION.SDK_INT >= 34 && caps?.hasTransport(NetworkCapabilities.TRANSPORT_THREAD) == true) add("Thread")
                if (Build.VERSION.SDK_INT >= 35 && caps?.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) == true) add("Спутниковая сеть")
            }
            AndroidNetworkRecord(
                id = network.networkHandle.toString(), name = links?.interfaceName ?: "Сеть ${network.networkHandle}",
                transports = transports, active = network == active,
                internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                ownerIsSelf = if (Build.VERSION.SDK_INT >= 30) caps?.ownerUid == android.os.Process.myUid() else false,
                addresses = links?.linkAddresses?.map { "${it.address.hostAddress}/${it.prefixLength}" }.orEmpty(),
                dns = links?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                routes = links?.routes?.map { it.destination.toString() to routeGateway(it.gateway?.hostAddress) }.orEmpty(),
                routeTypes = if (Build.VERSION.SDK_INT >= 33) links?.routes?.associate { route -> "${route.destination}:${routeGateway(route.gateway?.hostAddress)}" to route.type }.orEmpty() else emptyMap(),
                mtu = if (Build.VERSION.SDK_INT >= 29) links?.mtu ?: 0 else 0,
                proxy = links?.httpProxy?.let { proxy ->
                    if (proxy.pacFileUrl != android.net.Uri.EMPTY) "PAC настроен · содержимое не загружается"
                    else "${proxy.host}:${proxy.port}"
                } ?: "Не указан для этой сети",
                privateDns = if (Build.VERSION.SDK_INT >= 28 && links?.isPrivateDnsActive == true)
                    links.privateDnsServerName ?: "Активен · автоматический режим (opportunistic)"
                else "Android не сообщает активный Private DNS",
                propertiesAvailable = links != null, capabilitiesAvailable = caps != null,
                interfaceIndex = links?.interfaceName?.let { name -> runCatching { java.net.NetworkInterface.getByName(name)?.index }.getOrNull() } ?: 0,
            )
        }
        check(networks == manager.allNetworks.toSet() && active == manager.activeNetwork) { "Сеть изменилась во время чтения; обновите снимок" }
        return records
    }
}

/** Never access the getter before its public API introduction in Android 11. */
internal fun isOwnNetwork(sdk: Int, ownUid: Int, ownerUid: () -> Int): Boolean = sdk >= 30 && ownerUid() == ownUid

private fun routeGateway(address: String?): String = address?.takeUnless { it == "0.0.0.0" || it == "::" || it == "0:0:0:0:0:0:0:0" } ?: "на интерфейсе"

internal fun androidRouteKind(type: Int) = when (type) {
    RouteInfo.RTN_UNICAST -> "Передача трафика"
    RouteInfo.RTN_UNREACHABLE -> "Недоступное назначение · трафик отклоняется"
    RouteInfo.RTN_THROW -> "Продолжить поиск в другой таблице"
    else -> "Неизвестный тип маршрута ($type)"
}

internal class AndroidNetworkCollector(private val reader: AndroidNetworkReader) {
    fun collect(now: () -> Long = System::currentTimeMillis): NetworkSnapshot {
        val started = now()
        val result = runCatching { reader.read() }
        val records = result.getOrDefault(emptyList())
        val failure = result.exceptionOrNull()
        val state = when {
            failure is SecurityException -> SourceState.ACCESS_DENIED
            failure != null -> SourceState.ERROR
            records.isEmpty() -> SourceState.EMPTY
            else -> SourceState.AVAILABLE
        }
        val linksComplete = failure == null && records.all { it.propertiesAvailable }
        val capsComplete = failure == null && records.all { it.capabilitiesAvailable }
        val sources = listOf(
            ObservationSource("adapters", "Сети и интерфейсы", "Сети, которые Android разрешает видеть LerNET. Это не полный список устройства.", state,
                records.map { r -> EvidenceRow(r.id, r.name, linkedMapOf(
                    "Подключение" to r.transports.joinToString(" · ").ifBlank { "Тип не передан или пока не распознан" },
                    "Путь приложений" to if (r.active) "Сеть по умолчанию для LerNET" else "Дополнительная видимая сеть",
                    "Адреса устройства" to r.addresses.joinToString().ifBlank { "Не переданы системой" },
                    "MTU" to if (r.mtu > 0) "${r.mtu} байт" else "Не передан",
                    "Системная проверка интернета" to if (!r.capabilitiesAvailable) "Свойства недоступны" else if (r.validated) "Android подтвердил доступ" else "Android не подтвердил доступ",
                    "Доступ к данным" to if (r.propertiesAvailable && r.capabilitiesAvailable) "Свойства получены" else "Часть свойств недоступна",
                )) }, detail = failure?.let { if (it is SecurityException) "Android запретил чтение сведений сети" else "Не удалось прочитать согласованный снимок; обновите данные" }.orEmpty(), capturedAt = started, complete = linksComplete && capsComplete),
            ObservationSource("routes", "Маршруты", "Пути из LinkProperties. Android не раскрывает полную таблицу и метрики ОС.", state,
                records.flatMap { r -> r.routes.map { (prefix, gateway) -> EvidenceRow("${r.id}:$prefix:$gateway", prefix,
                    mapOf("Следующий узел" to gateway, "Интерфейс" to r.name, "Семейство" to if (':' in prefix) "IPv6" else "IPv4", "Действие" to androidRouteKind(r.routeTypes["$prefix:$gateway"] ?: RouteInfo.RTN_UNICAST))) } }, capturedAt = started, complete = linksComplete),
            ObservationSource("dns", "DNS и прокси", "Настройки адресов сайтов для каждой видимой сети. PAC-файлы не загружаются.", state,
                records.map { r -> EvidenceRow(r.id, r.name, linkedMapOf("DNS" to r.dns.joinToString().ifBlank { "Не переданы системой" },
                    "Private DNS" to r.privateDns, "Прокси" to r.proxy)) }, capturedAt = started, complete = linksComplete),
            ObservationSource("vpn", "VPN", "Видимый VPN transport не раскрывает настройки чужого приложения.", if (failure != null) state else if (records.none { it.vpn }) SourceState.EMPTY else SourceState.AVAILABLE,
                records.filter { it.vpn }.map { r -> EvidenceRow(r.id, r.name, mapOf(
                    "Владелец" to if (r.ownerIsSelf) "LerNET · UID совпадает с приложением" else "Android не раскрыл владельца",
                    "Протокол" to "Не передан Android", "Путь" to if (r.active) "По умолчанию для LerNET" else "Видимая дополнительная сеть")) }, capturedAt = started, complete = capsComplete),
            ObservationSource("platform-limits", "Границы наблюдения", "Обычное приложение Android видит свою область сети. Права root не требуются.", SourceState.UNSUPPORTED,
                detail = "Чужие процессы, фильтры ОС, полная таблица маршрутов и внутренние настройки другого VPN скрыты Android. Отсутствие VPN в этом снимке не доказывает, что на устройстве его нет.", capturedAt = started),
        )
        val snapshot = NetworkSnapshot(platform = "Android", startedAt = started, finishedAt = now(), userContext = "UID ${android.os.Process.myUid()}",
            adapters = records.map { r -> ObservedAdapter(r.id, r.interfaceIndex, r.name, r.transports.joinToString(), true, r.vpn, r.addresses, r.dns, mtu = r.mtu.takeIf { it > 0 }) },
            routes = records.flatMap { r -> r.routes.filter { (prefix, gateway) -> (r.routeTypes["$prefix:$gateway"] ?: RouteInfo.RTN_UNICAST) == RouteInfo.RTN_UNICAST }.map { (prefix, gateway) -> ObservedRoute(prefix, gateway, r.id, r.interfaceIndex, 0, store = "Android LinkProperties · метрика не раскрывается", metricsKnown = false) } }, sources = sources)
        return snapshot.copy(findings = NetworkObservationAnalysis.analyze(snapshot) + buildList {
            records.filter { it.active }.forEach { r -> add(NetworkFinding("android-default", "Сейчас выбран ${r.transports.joinToString().ifBlank { r.name }}",
                "Это сеть по умолчанию именно для LerNET на момент снимка. Отдельные приложения могут иметь другой путь.", FindingKind.FACT, listOf("adapters"), listOf(r.name))) }
            records.filter { it.active && it.capabilitiesAvailable && !it.validated }.forEach { r -> add(NetworkFinding("android-unvalidated", "Android не подтвердил интернет",
                "Системная проверка для ${r.name} не подтвердила доступ. Это повод проверить соединение, а не доказательство мёртвого туннеля.", FindingKind.INSUFFICIENT_DATA, listOf("adapters"))) }
        }.map { ObservationFindingGuide.explain(snapshot, it) })
    }
}
