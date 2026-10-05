package app.lernet.desktop

import app.lernet.engine.net.observation.NetworkRouteSelection
import java.net.InetAddress
import java.net.NetworkInterface

internal data class EndpointRouteObservation(val detail: String, val warning: String = "") {
    companion object {
        fun read(endpoint: String): EndpointRouteObservation {
            if (!System.getProperty("os.name").startsWith("Windows")) return EndpointRouteObservation("")
            if (!NetworkRouteSelection.isNumericAddress(endpoint)) return EndpointRouteObservation("Путь к серверу заданному именем не вычислен: пассивное наблюдение не выполняет DNS-запрос.")
            return runCatching {
                val index = WindowsRouteInspector.bestInterface(InetAddress.getByName(endpoint))
                val alias = WindowsRouteInspector.interfaceAlias(index)
                val description = NetworkInterface.getByIndex(index)?.displayName.orEmpty()
                describe(endpoint, index, alias, description)
            }.getOrElse { EndpointRouteObservation("Путь к серверу не прочитан: ${it.javaClass.simpleName}. Это не подтверждает отсутствие маршрута.") }
        }

        internal fun describe(endpoint: String, index: Int, alias: String, description: String): EndpointRouteObservation {
            val detail = "Путь Windows к $endpoint: «$alias», интерфейс $index, $description. Проверка без собственного TUN следует этому пути."
            val likelyTunnel = Regex("(?i)\\btun\\b|tunnel|tun2socks|\\btap\\b|tap-windows|wireguard|wintun|\\bvpn\\b").containsMatchIn(description)
            val warning = if (likelyTunnel && !WindowsRouteInspector.isLerNetInterface(alias))
                "Последняя проверка маршрута: путь к серверу через «$alias». Проверка LerNET может зависеть от другого VPN. Подробнее — «Сеть устройства»." else ""
            return EndpointRouteObservation(detail, warning)
        }
    }
}
