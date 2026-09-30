package app.lernet.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.lernet.engine.net.observation.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

/** Detached render only: no window, controller, VPN service, system command or network request. */
class NetworkObservationRenderTest {
    private var frameTime = 0L
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderReadingFlowAtDesktopAndCompactWidths() {
        val snapshot = fixtureSnapshot()
        val destination = File("build/observation-previews").apply { mkdirs() }
        for (width in listOf(1240, 780, 560)) {
            frameTime = 0L
            val scene = ImageComposeScene(width = width, height = 900) {
                MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                    DesktopNetworkObservation(snapshot, null, false, null,
                        onRefresh = {}, onExport = {}, onProbe = {}, probeResult = null,
                        onStartTrace = {}, onStopTrace = {}, traceRunning = false, traceStatus = "")
                }
            }
            try {
                pumpFrames(scene)
                saveFrame(scene, File(destination, "network-$width.png"))
                clickTag(scene, "network-finding-inactive-route", scroll = true)
                assertText(scene, "Как мы это определили")
                assertText(scene, "На что это может повлиять")
                scroll(scene, 510f)
                saveFrame(scene, File(destination, "network-$width-explanation.png"))
                val pages = linkedMapOf("ADAPTERS" to "adapters", "APPS" to "vpn-user", "ROUTES" to "routes",
                    "DNS" to "dns", "FILTERS" to "wfp", "SYSTEM" to "bindings", "EVENTS" to "events")
                pages.forEach { (page, source) ->
                    clickTag(scene, "network-nav-$page")
                    assertTrue(allNodes(scene).any { tag(it) == "network-nav-$page" &&
                        it.config.getOrElseNullable(SemanticsProperties.Selected) { null } == true })
                    clickTag(scene, "network-source-$source", scroll = true)
                    clickTag(scene, "network-guide-$source", scroll = true)
                    assertText(scene, "На что смотрим")
                    if (page == "DNS") {
                        clickTag(scene, "network-row-wifi-dns", scroll = true)
                        clickText(scene, "Поля и их смысл")
                        assertText(scene, "DNS-серверы")
                    }
                    if (page == "DNS") scroll(scene, 520f)
                    if (page == "ADAPTERS" || page == "DNS") saveFrame(scene, File(destination, "network-$width-${source}-guide.png"))
                }
                clickTag(scene, "network-nav-ROUTES")
                saveFrame(scene, File(destination, "network-$width-routes.png"))
                assertTrue(allNodes(scene).any { tag(it) == "network-route-playground" })
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun allNodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickTag(scene: ImageComposeScene, wanted: String, scroll: Boolean = false) {
        var match = allNodes(scene).firstOrNull { tag(it) == wanted }
        if (match == null && scroll) repeat(12) {
            if (match == null) {
                allNodes(scene).filter { it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.maxByOrNull { it.boundsInRoot.width }?.let {
                    it.config[SemanticsActions.ScrollBy].action?.invoke(0f, 180f)
                }
                pumpFrames(scene)
                match = allNodes(scene).firstOrNull { tag(it) == wanted }
            }
        }
        assertTrue("Missing click target $wanted", match != null)
        assertTrue(checkNotNull(match!!.config[SemanticsActions.OnClick].action).invoke())
        pumpFrames(scene)
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun scroll(scene: ImageComposeScene, distance: Float) {
        allNodes(scene).filter { it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.maxByOrNull { it.boundsInRoot.width }?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, distance)
        pumpFrames(scene)
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickText(scene: ImageComposeScene, wanted: String) {
        val node = allNodes(scene).first { it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { text -> text.text.contains(wanted) } == true }
        assertTrue(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        pumpFrames(scene)
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun assertText(scene: ImageComposeScene, wanted: String) {
        assertTrue("Missing explanation $wanted", allNodes(scene).any { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text.contains(wanted) } == true
        })
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun pumpFrames(scene: ImageComposeScene) {
        repeat(8) {
            Snapshot.sendApplyNotifications()
            frameTime += 50_000_000
            scene.render(frameTime).close()
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    private fun saveFrame(scene: ImageComposeScene, destination: File) {
        frameTime += 50_000_000
        val image = scene.render(frameTime)
        try {
            val encoded = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
            try { destination.writeBytes(encoded.bytes) }
            finally { encoded.close() }
        } finally { image.close() }
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun tag(node: SemanticsNode): String? = node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }

    private fun fixtureSnapshot(): NetworkSnapshot {
        val time = 1_790_780_000_000
        val adapters = listOf(
            ObservedAdapter("wifi-guid", 8, "Wi-Fi", "Intel Wi-Fi · используемая сеть", true,
                addresses = listOf("192.0.2.22/24"), dns = listOf("192.0.2.1"), metric = 25, mtu = 1500),
            ObservedAdapter("ethernet-guid", 4, "Ethernet", "Intel Ethernet · кабель отключён", false,
                addresses = listOf("198.51.100.22/24"), metric = 5, mtu = 1500),
        )
        val routes = listOf(ObservedRoute("0.0.0.0/0", "192.0.2.1", "wifi-guid", 8, 0, 25),
            ObservedRoute("0.0.0.0/0", "198.51.100.1", "ethernet-guid", 4, 0, 5),
            ObservedRoute("224.0.0.0/4", "0.0.0.0", "ethernet-guid", 4, 256, 5))
        fun source(id: String, title: String, vararg rows: EvidenceRow) = ObservationSource(id, title, "", rows = rows.toList(), capturedAt = time)
        val sources = listOf(
            source("adapters", "Сетевые адаптеры", *adapters.map { EvidenceRow(it.id, it.name,
                linkedMapOf("Status" to if (it.up) "Up" else "Disconnected", "InterfaceDescription" to it.description,
                    "InterfaceIndex" to it.index.toString(), "MTU" to "1500")) }.toTypedArray()),
            source("routes", "Таблица маршрутов", *routes.mapIndexed { index, route -> EvidenceRow("route-$index", route.prefix,
                linkedMapOf("DestinationPrefix" to route.prefix, "NextHop" to route.nextHop,
                    "InterfaceIndex" to route.interfaceIndex.toString(), "RouteMetric" to route.metric.toString())) }.toTypedArray()),
            source("dns", "DNS-серверы", EvidenceRow("wifi-dns", "Wi-Fi", mapOf("InterfaceAlias" to "Wi-Fi", "ServerAddresses" to "192.0.2.1"))),
            source("vpn-user", "Профили VPN пользователя", EvidenceRow("office", "Корпоративный VPN", mapOf("TunnelType" to "Sstp", "ConnectionStatus" to "Disconnected", "SplitTunneling" to "True"))),
            source("wfp", "Фильтры Windows", EvidenceRow("filter", "Пример фильтра", mapOf("Action" to "Allow", "Direction" to "Outbound", "Conditions" to "Приложение и адрес"))),
            source("bindings", "Компоненты адаптеров", EvidenceRow("binding", "IPv4 · Wi-Fi", mapOf("InterfaceAlias" to "Wi-Fi", "Enabled" to "True", "ComponentID" to "ms_tcpip"))),
            source("events", "События сети", EvidenceRow("event", "Смена сети", mapOf("Event ID" to "10000", "Источник" to "NetworkProfile", "Время UTC" to "2026-09-30T10:00:00Z"))),
        )
        val base = NetworkSnapshot(platform = "Windows · демонстрационные данные", startedAt = time - 3000, finishedAt = time,
            elevated = true, adapters = adapters, routes = routes, sources = sources)
        return base.copy(findings = NetworkObservationAnalysis.analyze(base))
    }
}
