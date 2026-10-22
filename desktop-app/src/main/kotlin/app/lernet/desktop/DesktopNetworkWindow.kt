package app.lernet.desktop

import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.net.LocalProxyHttp
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.lernet.desktop.observation.*
import app.lernet.engine.net.LocalGeoIp
import app.lernet.engine.net.observation.*
import java.awt.FileDialog
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** This window never owns the VPN controller. Closing it only cancels its own observation work. */
@Composable
internal fun DesktopNetworkWindow(onClose: () -> Unit, useLerNetProxy: () -> LocalProxyAccess?, readClientContext: () -> ClientObservationContext, readDiagnosticLog: () -> List<String> = { emptyList() }) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(ObservationSnapshotHistory()) }
    val snapshot = history.report
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    val ipConnection = remember { ObservationConnectionSlot() }
    var ipJob by remember { mutableStateOf<Job?>(null) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val reportJson = remember { Json { prettyPrint = true } }
    val trace = remember { WindowsObservationTrace(DesktopStore.defaultDirectory().resolve("network-observation").toFile()) }
    val traceState by trace.state.collectAsState()
    val refresh: () -> Unit = {
        if (!busy && refreshJob?.isActive != true) {
            ipJob?.cancel()
            ipConnection.cancelRequest()
            probing = false; probeResult = null
            val clientContext = readClientContext()
            busy = true; error = null
            refreshJob = scope.launch {
                try {
                    val next = runInterruptible(Dispatchers.IO) { withClientContext(WindowsNetworkObservation().collect(), clientContext) }
                    history = history.refreshed(next)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = app.lernet.engine.text.UserText.of(failure, "Не удалось прочитать настройки Windows") }
                finally { busy = false }
            }
        }
    }
    DisposableEffect(trace) {
        onDispose {
            ipConnection.close()
            Thread({ trace.close() }, "LerNET-observation-cleanup").apply { isDaemon = true; start() }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(traceState.phase, traceState.file) {
        val file = traceState.file
        if (traceState.phase == ObservationTracePhase.STARTING) history = history.clearRecording()
        if (traceState.phase == ObservationTracePhase.FINISHED && file != null) {
            val events = runInterruptible(Dispatchers.IO) { WindowsObservationEvents().readTrace(file) }
            history = history.recorded(events)
        }
    }
    Window(onCloseRequest = onClose, title = "Анализ сети · LerNET", icon = painterResource("lernet-icon.png"),
        state = rememberWindowState(size = DpSize(1220.dp, 840.dp))) {
        LaunchedEffect(window) { WindowsTitleBar.dark(window) }
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
            DesktopNetworkObservation(snapshot, history.comparisonBaseline, busy, error, refresh,
                onExport = {
                    val current = history.exportReport(readDiagnosticLog())
                    if (current != null) {
                        val dialog = FileDialog(window, "Сохранить локальный отчёт сети", FileDialog.SAVE)
                        dialog.file = "LerNET-network-${current.finishedAt}.json"
                        dialog.isVisible = true
                        val path = dialog.file?.let { java.nio.file.Path.of(dialog.directory, it) }
                        dialog.dispose()
                        if (path != null) scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Files.writeString(path, reportJson.encodeToString(current)) }
                                probeResult = "Отчёт сохранён: $path"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = app.lernet.engine.text.UserText.withReason("Не удалось сохранить отчёт", failure) }
                        }
                    }
                },
                onProbe = {
                    if (!probing) {
                        val request = ipConnection.beginRequest()
                        probing = true
                        ipJob = scope.launch {
                            probeResult = "Запрашиваем внешний адрес у api.ipify.org…"
                            try {
                                val proxy = useLerNetProxy()
                                val result = runInterruptible(Dispatchers.IO) { ObservationExternalIp.read(proxy, onConnection = { ipConnection.attach(it, request) }, onCall = { ipConnection.attach(it, request) }) }
                                if (ipConnection.isCurrent(request)) probeResult = result
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { if (ipConnection.isCurrent(request)) probeResult = app.lernet.engine.text.UserText.withReason("Адрес не получен", failure).trimEnd('.') + ". Это не доказывает, что весь интернет недоступен." }
                            finally {
                                ipConnection.release(request)
                                if (ipConnection.isCurrent(request)) probing = false
                            }
                        }
                    }
                }, probeResult = probeResult,
                onStartTrace = { history = history.clearRecording(); scope.launch { runInterruptible(Dispatchers.IO) { trace.start() } } },
                onStopTrace = { scope.launch { runInterruptible(Dispatchers.IO) { trace.stop() } } },
                traceRunning = traceState.phase in setOf(ObservationTracePhase.STARTING, ObservationTracePhase.RECORDING, ObservationTracePhase.STOPPING),
                onCancel = { refreshJob?.cancel() },
                comparisonSnapshot = history.current,
                onPinBaseline = { history = history.pinBaseline() },
                baselinePinned = history.anchor != null,
                traceStatus = traceState.title + " · " + traceState.detail + (traceState.file?.let { "\n${it.absolutePath}" } ?: ""),
            )
        }
    }
}

internal object ObservationExternalIp {
    private const val URL = "https://api.ipify.org"

    /**
     * [access] set: the request goes only through LerNET's local proxy with its login and password,
     * never directly. Null: the request follows the current Windows routes (VPN mode included).
     */
    fun read(
        access: LocalProxyAccess?,
        onConnection: (HttpURLConnection) -> Unit = {},
        openConnection: (Proxy) -> HttpURLConnection = { URI(URL).toURL().openConnection(it) as HttpURLConnection },
        onCall: (Call) -> Unit = {},
        proxyClient: (LocalProxyAccess) -> OkHttpClient = ::proxyClient,
        countryLookup: (String) -> String? = { ip -> LocalGeoIp { requireNotNull(javaClass.getResourceAsStream("/hop-geoip.idx")) }.country(ip) },
    ): String {
        val bytes = if (access != null) readThroughProxy(proxyClient(access), onCall) else readDirect(onConnection, openConnection)
        val ip = bytes.toString(Charsets.UTF_8).trim()
        check(bytes.size <= 64 && NetworkRouteSelection.isNumericAddress(ip)) { "Сервис вернул неожиданный ответ" }
        val country = runCatching { countryLookup(ip) }.getOrNull()
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        return "Внешний ${if (':' in ip) "IPv6" else "IPv4"}: $ip${country?.let { " · страна выхода $it (локальная база)" }.orEmpty()}\n" +
            "api.ipify.org · $time · ${if (access != null) "через VPN LerNET" else "напрямую, по текущим маршрутам Windows"}. " +
            "Это адрес выхода для этого запроса. Провайдер и домашний IP за VPN отсюда не определяются."
    }

    private fun readDirect(onConnection: (HttpURLConnection) -> Unit, openConnection: (Proxy) -> HttpURLConnection): ByteArray {
        val connection = openConnection(Proxy.NO_PROXY)
        try {
            onConnection(connection)
            connection.connectTimeout = 30_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept", "text/plain")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readNBytes(65) }
        } finally { connection.disconnect() }
    }

    private fun readThroughProxy(client: OkHttpClient, onCall: (Call) -> Unit): ByteArray {
        val request = Request.Builder().url(URL).header("Cache-Control", "no-cache").header("Accept", "text/plain").build()
        val call = client.newCall(request)
        try {
            onCall(call)
            return call.execute().use { response ->
                check(response.code == 200) { "HTTP ${response.code}" }
                response.body?.byteStream()?.use { it.readNBytes(65) } ?: ByteArray(0)
            }
        } finally { call.cancel() }
    }

    internal fun proxyClient(access: LocalProxyAccess): OkHttpClient =
        LocalProxyHttp.via(OkHttpClient.Builder(), access)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .cache(null)
            .build()
}
