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
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderObservationAtDesktopAndCompactWidths() {
        val snapshot = fixtureSnapshot()
        val destination = File("build/observation-previews").apply { mkdirs() }
        for (width in listOf(1240, 780, 560)) {
            val scene = ImageComposeScene(width = width, height = 900) {
                MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                    DesktopNetworkObservation(snapshot, null, false, null,
                        onRefresh = {}, onExport = {}, onProbe = {}, probeResult = null,
                        onStartTrace = {}, onStopTrace = {}, traceRunning = false, traceStatus = "")
                }
            }
            try {
                pumpFrames(scene, 0)
                saveFrame(scene, File(destination, "network-$width.png"), 500_000_000)
                val routeEntry = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.first { node ->
                    node.config.contains(SemanticsActions.OnClick) && tag(node) == "network-nav-ROUTES"
                }
                assertTrue(checkNotNull(routeEntry.config[SemanticsActions.OnClick].action).invoke())
                pumpFrames(scene, 600_000_000)
                saveFrame(scene, File(destination, "network-$width-routes.png"), 1_100_000_000)
                assertTrue(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { node ->
                    tag(node) == "network-route-playground"
                })
                assertTrue(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { node ->
                    tag(node) == "network-nav-ROUTES" && node.config.getOrElseNullable(SemanticsProperties.Selected) { null } == true
                })
            } finally { scene.close() }
        }
    }

    private fun pumpFrames(scene: ImageComposeScene, start: Long) {
        repeat(8) { frame ->
            Snapshot.sendApplyNotifications()
            scene.render(start + frame * 50_000_000).close()
        }
    }

    private fun saveFrame(scene: ImageComposeScene, destination: File, time: Long) {
        val image = scene.render(time)
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
            ObservedAdapter("wifi-guid", 8, "Wi-Fi", "Беспроводная сеть", true, addresses = listOf("192.168.1.22"), dns = listOf("192.168.1.1"), metric = 25, mtu = 1500),
            ObservedAdapter("vpn-guid", 17, "LerNET", "Виртуальный адаптер", true, virtual = true, addresses = listOf("172.19.0.1"), metric = 5, mtu = 1500),
            ObservedAdapter("wsl-guid", 33, "vEthernet (WSL)", "Hyper-V", false, virtual = true),
        )
        val routes = listOf(ObservedRoute("0.0.0.0/0", "192.168.1.1", "wifi-guid", 8, 0, 25),
            ObservedRoute("0.0.0.0/1", "0.0.0.0", "vpn-guid", 17, 0, 5),
            ObservedRoute("128.0.0.0/1", "0.0.0.0", "vpn-guid", 17, 0, 5))
        val sources = listOf(
            ObservationSource("adapters", "Сетевые адаптеры", "Имена и состояние интерфейсов Windows.", rows = adapters.map {
                EvidenceRow(it.id, it.name, mapOf("Состояние" to if (it.up) "Включён" else "Отключён", "Описание" to it.description, "MTU" to (it.mtu?.toString() ?: "Не указан")))
            }, capturedAt = time),
            ObservationSource("routes", "Таблица маршрутов", "Правила выбора выхода для каждого адреса.", rows = routes.map {
                EvidenceRow(it.prefix, it.prefix, mapOf("Шлюз" to it.nextHop, "Интерфейс" to it.interfaceIndex.toString()))
            }, capturedAt = time),
            ObservationSource("wfp", "Фильтры Windows", "Правила и компоненты фильтрации.", state = SourceState.ACCESS_DENIED,
                detail = "Источник не прочитан: нужны дополнительные права.", capturedAt = time),
        )
        val base = NetworkSnapshot(platform = "Windows · тестовый снимок", startedAt = time - 3000, finishedAt = time,
            elevated = true, adapters = adapters, routes = routes, sources = sources)
        return base.copy(findings = NetworkObservationAnalysis.analyze(base))
    }
}
