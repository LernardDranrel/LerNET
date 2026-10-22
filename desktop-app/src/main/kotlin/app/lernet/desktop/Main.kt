package app.lernet.desktop

import app.lernet.config.net.LocalProxyAccess

import app.lernet.engine.policy.ExpertVpnHandover
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.NetworkPolicy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.saveable.rememberSaveable
import app.lernet.desktop.expert.DesktopExpertSplash
import app.lernet.desktop.expert.rememberDesktopExpertSplash
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import app.lernet.engine.RunMode
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.OutboundPatch
import app.lernet.config.model.DnsPolicy
import app.lernet.desktop.expert.DesktopExpert
import app.lernet.desktop.expert.ExpertIntent
import app.lernet.desktop.expert.ExpertPhase
import java.awt.Toolkit
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay

private val background = Color(0xFF0B1019)
private val panel = Color(0xFF151D2B)
private val blue = Color(0xFF91ABFF)
private val muted = Color(0xFFA1AEC4)
private val green = Color(0xFF80DEBE)
/** The one extra palette colour: amber for «нет связи · VPN держим» (same value as LerNetHold on Android). */
internal val hold = Color(0xFFF0A93B)
internal val APP_VERSION: String = AppVersionResource::class.java.getResourceAsStream("/lernet-version.txt")
    ?.bufferedReader()?.use { it.readText().trim() }?.takeIf { it.isNotBlank() } ?: "dev"
private object AppVersionResource
private data class ExpertArchivePreview(val raw: String, val fileName: String, val profiles: Int, val folders: Int)
internal val desktopColors = darkColorScheme(
    primary = blue, onPrimary = background,
    primaryContainer = Color(0xFF293B62), onPrimaryContainer = Color(0xFFE1E8FF),
    secondary = green, secondaryContainer = Color(0xFF23473F), onSecondaryContainer = Color(0xFFD2F8EA),
    background = background, onBackground = Color(0xFFF5F7FB),
    surface = Color(0xFF101724), onSurface = Color(0xFFECF0F8),
    surfaceVariant = panel, onSurfaceVariant = muted,
    outline = Color(0xFF3B4961), outlineVariant = Color(0xFF253249), error = Color(0xFFFFB4AB),
)
internal val desktopTypography = Typography(
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
)

fun main(args: Array<String>) {
    val startupCheck = args.firstOrNull { it.startsWith("--verify-startup=") }?.substringAfter('=')
    // Started by «Запускать при входе в систему»: open in the tray and bring back a VPN that was left on.
    val autostart = WindowsAutostart.ARGUMENT in args
    var startupFallbackMessage: String? = null
    if (startupCheck == null && System.getProperty("os.name").startsWith("Windows")) {
        val saved = runCatching { DesktopStore().load() }.getOrDefault(StoredState())
        if (autostart && !saved.autostart) return
        if (DesktopStartup.needsElevation(saved, WindowsElevation.isElevated)) {
            val elevation = WindowsElevation.relaunchAsAdministrator(if (autostart) WindowsAutostart.ARGUMENT else null)
            if (elevation.isSuccess) return
            startupFallbackMessage = "Не удалось запустить VPN от имени администратора. Доступен локальный прокси: " +
                (elevation.exceptionOrNull()?.let { app.lernet.engine.text.UserText.of(it, "запрос прав отменён") } ?: "запрос прав отменён")
        }
    }
    desktopApplication(startupCheck, startupFallbackMessage, autostart)
}

private fun desktopApplication(startupCheck: String?, startupFallbackMessage: String?, autostart: Boolean = false) = application {
    val controller = remember {
        DesktopController().also {
            startupFallbackMessage?.let(it::showMessage)
            if (startupCheck == null) {
                it.startSubscriptionSchedule()
                it.syncAutostart()
                // The VPN comes back only if the user left it on; «Отключить» clears that.
                if (autostart && it.sessionMarks.resumeWanted) it.connect()
            }
        }
    }
    val expert = remember { if (startupCheck == null) DesktopExpertController(controller) else null }
    DisposableEffect(controller, expert) {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val closeController = { if (closed.compareAndSet(false, true)) { expert?.close(); controller.close() } }
        val hook = Thread({ closeController() }, "LerNET-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        val listener = if (startupCheck == null) WindowsUpdateShutdown.listen {
            javax.swing.SwingUtilities.invokeLater { closeController(); exitApplication() }
        } else null
        onDispose {
            listener?.close()
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            closeController()
        }
    }
    var visible by remember { mutableStateOf(!autostart) }
    var networkWindowOpen by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    val tunnel by controller.tunnel.state.collectAsState()
    val ui by controller.state.collectAsState()
    val expertUi = expert?.state?.collectAsState()?.value
    val modeStates = rememberSaveableStateHolder()
    var expertTab by rememberSaveable { mutableStateOf(false) }
    var expertConfirm by remember { mutableStateOf<ExpertIntent?>(null) }
    var expertImport by remember { mutableStateOf<ExpertArchivePreview?>(null) }
    var expertReplaceConfirm by remember { mutableStateOf(false) }
    var expertStartPending by remember { mutableStateOf(false) }
    var expertHandover by remember { mutableStateOf<Pair<String, NetworkPolicy>?>(null) }
    var keepVpn by remember { mutableStateOf(true) }
    var expertStartPreflight by remember { mutableStateOf<ExpertPreflightResult?>(null) }
    LaunchedEffect(expertStartPending) {
        if (expertStartPending) expertStartPreflight = WindowsExpertPreflight().read()
    }
    val icon = painterResource(
        when {
            expertUi?.phase == ExpertPhase.RUNNING -> "lernet-icon-running.png"
            expertUi?.phase == ExpertPhase.FAILED -> "lernet-icon-failed.png"
            else -> when (tunnel.status) {
            TunnelStatus.RUNNING -> when {
                ui.healthFailures >= 2 -> "lernet-icon-failed.png"
                ui.healthVerified == true -> "lernet-icon-running.png"
                else -> "lernet-icon.png"
            }
            TunnelStatus.FAILED -> "lernet-icon-failed.png"
            else -> "lernet-icon.png"
            }
        },
    )
    Tray(
        icon = icon,
        tooltip = "LerNET",
        onAction = { visible = true },
        menu = {
            Item("Открыть", onClick = { visible = true })
            Item("Сеть устройства", onClick = { networkWindowOpen = true })
            Item("Отключить", onClick = {
                if (expertUi?.phase in setOf(ExpertPhase.RUNNING, ExpertPhase.STARTING, ExpertPhase.APPLYING, ExpertPhase.FAILED)) {
                    expert?.dispatch(ExpertIntent.Stop)
                }
                else controller.disconnect()
            })
            Separator()
            Item("Выход", onClick = { expert?.close(); controller.close(); exitApplication() })
        },
    )
    Window(
        title = "LerNET",
        state = windowState,
        icon = icon,
        visible = visible,
        onCloseRequest = { visible = false },
    ) {
        LaunchedEffect(window) {
            // The AWT peer may not exist on the first composition. Apply the
            // DWM preference after the window becomes visible and through startup.
            repeat(8) {
                delay(250)
                WindowsTitleBar.dark(window)
            }
            if (startupCheck != null) {
                Files.writeString(Path.of(startupCheck), "ready")
                controller.close()
                exitApplication()
            }
        }
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
            Surface(color = background, contentColor = desktopColors.onBackground) {
                if (startupCheck == null) DesktopUpdatePrompt()
                Column(Modifier.fillMaxSize()) {
                    DesktopModeTabs(expertTab, { expertTab = it },
                        status = when {
                            expertUi?.phase == ExpertPhase.RUNNING -> "Управление включено"
                            tunnel.status == TunnelStatus.RUNNING -> "VPN подключён"
                            else -> ""
                        }, reducedMotion = expertUi?.reducedMotion == true)
                    Crossfade(expertTab, Modifier.weight(1f).fillMaxWidth(),
                        animationSpec = tween(if (expertUi?.reducedMotion == true) 0 else 180), label = "workspace") { showExpert ->
                        modeStates.SaveableStateProvider(showExpert) {
                            if (showExpert && expert != null && expertUi != null) {
                                val splash = rememberDesktopExpertSplash()
                                Box(Modifier.fillMaxSize()) {
                                DesktopExpert(expertUi, frameAnimateIn = !splash.firstEntry, onIntent = { intent ->
                                    when (intent) {
                                        ExpertIntent.OpenNetworkObservation -> networkWindowOpen = true
                                        ExpertIntent.Import -> chooseTransferFile(false)?.let { file ->
                                            try {
                                                check(Files.size(file) <= 20_000_000)
                                                val raw = Files.readString(file)
                                                val preview = app.lernet.config.policy.PolicyWorkspaceCodec.decode(raw)
                                                expertImport = ExpertArchivePreview(raw, file.fileName.toString(), preview.legacy.profiles.size, preview.legacy.groups.size)
                                                expertReplaceConfirm = false
                                            } catch (_: Exception) { expert.reportError("Не удалось прочитать архив LerNET. Проверьте формат и размер файла (до 20 МБ).") }
                                        }
                                        ExpertIntent.Export, ExpertIntent.EnableSystemGuard, ExpertIntent.RecoverSystemGuard -> expertConfirm = intent
                                        ExpertIntent.ChooseExecutable -> {
                                            val dialog = FileDialog(null as Frame?, "Выбрать исполняемый файл", FileDialog.LOAD)
                                            dialog.isVisible = true
                                            dialog.file?.let { expert.addExecutable(Path.of(dialog.directory, it).toString()) }
                                            dialog.dispose()
                                        }
                                        ExpertIntent.Start -> {
                                            expertHandover = controller.connectedVpnProfileId()?.let { profileId ->
                                                expertUi.saved.takeIf {
                                                    ExpertVpnHandover.available(it, expertUi.draft)
                                                }?.let { profileId to it }
                                            }
                                            keepVpn = true
                                            expertStartPreflight = null
                                            expertStartPending = true
                                        }
                                        else -> expert.dispatch(intent)
                                    }
                                })
                                if (splash.visible) DesktopExpertSplash()
                                }
                            } else DesktopScreen(controller, onOpenNetwork = { networkWindowOpen = true }, onRequestElevation = {
                                WindowsElevation.relaunchAsAdministrator()
                                    .onSuccess { expert?.close(); controller.close(); exitApplication() }
                                    .onFailure { controller.showMessage(app.lernet.engine.text.UserText.of(it, "Не удалось запросить права администратора")) }
                            })
                        }
                    }
                }
                if (expertStartPending) {
                    AlertDialog(
                        onDismissRequest = { expertStartPending = false },
                        title = { Text("Включить управление сетью?") },
                        text = {
                            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("LerNET станет посредником для сетевых соединений устройства. " +
                                    "Они будут следовать сохранённой схеме; путь по умолчанию указан в её корне.")
                                expertHandover?.let { handover ->
                                    val name = ui.saved.profiles.firstOrNull { it.id == handover.first }?.name.orEmpty()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = keepVpn, onCheckedChange = { keepVpn = it })
                                        Text("Сохранить VPN «$name» как основной путь")
                                    }
                                    Text(if (keepVpn) "Пустая схема направит трафик через этот конфиг и его правила." else
                                        "VPN будет отключён. Трафик пойдёт напрямую; заблокированные в вашей сети сайты могут перестать открываться.")
                                }
                                if (expertHandover == null && expertUi?.saved?.device?.defaultTarget ==
                                    PolicyTarget.Direct) {
                                    Text("Основной путь схемы — напрямую. Если обычный VPN подключён, он будет отключён. " +
                                        "Без отдельного правила сайты пойдут через вашу обычную сеть.")
                                }
                                if (!WindowsElevation.isElevated) {
                                    Text("Windows запросит права администратора и откроет новую копию LerNET. " +
                                        "Текущее подключение будет остановлено. После этого откройте экспертный режим и подтвердите включение.")
                                }
                                if (tunnel.status in setOf(TunnelStatus.RUNNING, TunnelStatus.STARTING)) {
                                    Text("Обычное подключение LerNET будет остановлено перед переключением. " +
                                        "В текущей копии с правами администратора, если прежнее подключение уже работало, " +
                                        "после ошибки запуска приложение попытается его восстановить.")
                                }
                                val snapshot = expertStartPreflight
                                if (snapshot == null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        Text("Читаем текущие пути Windows…")
                                    }
                                } else {
                                    Text(if (snapshot.complete) "Сетевые настройки прочитаны" else
                                        "Часть сетевых сведений недоступна", fontWeight = FontWeight.SemiBold)
                                    Text("Это чтение настроек Windows, а не проверка Интернета. " +
                                        "Отсутствие IPv6-пути само по себе не означает конфликт VPN.",
                                        style = MaterialTheme.typography.bodySmall)
                                    var details by remember(snapshot.checkedAtMs) { mutableStateOf(false) }
                                    TextButton(onClick = { details = !details }) {
                                        Text(if (details) "Скрыть подробности ▴" else "Маршруты и ограничения проверки ▾")
                                    }
                                    if (details) {
                                        snapshot.facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        snapshot.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        },
                        confirmButton = { Button(enabled = expertStartPreflight != null, onClick = {
                            expertStartPending = false
                            if (!WindowsElevation.isElevated) {
                                WindowsElevation.relaunchAsAdministrator()
                                    .onSuccess { expert?.close(); controller.close(); exitApplication() }
                                    .onFailure { expert?.reportError(app.lernet.engine.text.UserText.of(it, "Не удалось запросить права администратора")) }
                            } else {
                                val handover = expertHandover.takeIf { keepVpn }
                                if (handover != null) expert?.startKeepingVpn(handover.first, handover.second)
                                else expert?.dispatch(ExpertIntent.Start)
                            }
                        }) { Text(if (expertHandover != null && keepVpn) "Сохранить VPN и включить" else "Включить") } },
                        dismissButton = { TextButton(onClick = { expertStartPending = false }) { Text("Отмена") } },
                    )
                }
                expertConfirm?.let { action ->
                    AlertDialog(onDismissRequest = { expertConfirm = null },
                        title = { Text(when (action) { ExpertIntent.Export -> "Сохранить рабочую область?"; ExpertIntent.EnableSystemGuard -> "Защитить всё устройство?"; else -> "Восстановить обычный доступ в сеть?" }) },
                        text = { Text(when (action) {
                            ExpertIntent.Export -> "Архив содержит настройки подключения и секреты конфигов. Передавайте его только доверенным людям."
                            ExpertIntent.EnableSystemGuard -> "При остановке LerNET Windows будет блокировать обычный выход " +
                                "в сеть для всего устройства. Разрешённые ветки внутри работающего туннеля смогут выходить напрямую. " +
                                "Внешние соединения другого VPN-клиента тоже могут быть заблокированы, " +
                                "включая его доступ к корпоративной сети. Обычные связи программ с localhost тоже блокируются. " +
                                "Для возврата обычной сети отдельно отключите " +
                                "системную защиту в разделе «Защита»."
                            else -> "Будут удалены только системные фильтры LerNET. После выключения туннеля приложения смогут использовать обычную сеть."
                        }) },
                        confirmButton = { Button(onClick = {
                            expertConfirm = null
                            when (action) {
                                ExpertIntent.Export -> chooseTransferFile(true, "LerNET-workspace.json")?.let { expert?.exportArchive(it) }
                                ExpertIntent.EnableSystemGuard -> expert?.enableSystemGuard()
                                ExpertIntent.RecoverSystemGuard -> expert?.dispatch(action)
                                else -> Unit
                            }
                        }) { Text("Продолжить") } }, dismissButton = { TextButton(onClick = { expertConfirm = null }) { Text("Отмена") } })
                }
                expertImport?.let { archive ->
                    AlertDialog(onDismissRequest = { expertImport = null },
                        title = { Text(if (expertReplaceConfirm) "Заменить всю рабочую область?" else "Импорт рабочей области") },
                        text = { Text(if (expertReplaceConfirm)
                            "Текущие конфиги, папки и сохранённая схема будут заменены содержимым «${archive.fileName}». Активные правила не изменятся до вашего отдельного применения."
                        else "${archive.fileName}\nКонфигов: ${archive.profiles} · папок: ${archive.folders}.\nДобавление создаёт копии с новыми идентификаторами. Замена переносит всю рабочую область, включая основной путь по умолчанию. Импорт не включает новые правила в работающем туннеле.") },
                        confirmButton = { Button(onClick = {
                            expert?.importArchive(archive.raw, replace = expertReplaceConfirm)
                            expertImport = null
                        }) { Text(if (expertReplaceConfirm) "Заменить" else "Добавить копии") } },
                        dismissButton = { Row {
                            if (!expertReplaceConfirm) TextButton(onClick = { expertReplaceConfirm = true }) { Text("Заменить всё…") }
                            TextButton(onClick = { expertImport = null }) { Text("Отмена") }
                        } })
                }
            }
        }
    }
    if (networkWindowOpen) DesktopNetworkWindow(
        readDiagnosticLog = { controller.tunnel.state.value.logs },
        onClose = { networkWindowOpen = false },
        // Same route as LerNET's other requests: through VPN when it is on, refused while it recovers.
        useLerNetProxy = {
            when (val route = app.lernet.config.net.AppTraffic.route) {
                is app.lernet.config.net.AppTraffic.Route.Through -> route.access
                app.lernet.config.net.AppTraffic.Route.Closed -> throw app.lernet.config.net.AppTraffic.ClosedException()
                app.lernet.config.net.AppTraffic.Route.Direct -> null
            }
        },
        readClientContext = {
            val saved = controller.state.value.saved
            val profile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
            val outbound = profile?.outbounds?.firstOrNull { it.id == profile.selectedOutboundId }
            val endpoint = runCatching { outbound?.singBoxJson?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["server"]?.jsonPrimitive?.content } }.getOrNull().orEmpty()
            app.lernet.desktop.observation.ClientObservationContext(profile?.name.orEmpty(), endpoint, outbound?.type.orEmpty(), profile?.modeOverride ?: saved.mode, controller.tunnel.state.value.status.name, controller.tunnel.state.value.message, APP_VERSION)
        },
    )
}

private enum class Tab(val label: String) {
    PROFILES("Главная"), ROUTES("Правила"), DIAGNOSTICS("Журнал запросов"), SETTINGS("Настройки")
}
/** Fixed state for screenshot tests: [page] is a page name (PROFILES, ROUTES, DIAGNOSTICS, SETTINGS). */
internal data class DesktopScreenPreview(
    val tunnel: TunnelSnapshot? = null,
    val ui: ((DesktopUiState) -> DesktopUiState)? = null,
    val page: String? = null,
)
private data class ExportSelection(val groupId: String?, val suggestedName: String)

@Composable
internal fun DesktopScreen(controller: DesktopController, onRequestElevation: () -> Unit, onOpenNetwork: () -> Unit,
    preview: DesktopScreenPreview? = null) {
    val liveUi by controller.state.collectAsState()
    val liveTunnel by controller.tunnel.state.collectAsState()
    // Screenshot tests show every state without starting a real core; the app itself never passes a preview.
    val ui = preview?.ui?.invoke(liveUi) ?: liveUi
    val tunnel = preview?.tunnel ?: liveTunnel
    val diagnostics by controller.diagnostics.collectAsState()
    val connectionHistory by controller.connectionHistory.collectAsState()
    val trace by controller.tracer.state.collectAsState()
    // v2: launch and every mode change open the basic home, not the last page.
    var tab by remember { mutableStateOf(preview?.page?.let { p -> Tab.entries.firstOrNull { it.name == p } } ?: Tab.PROFILES) }
    var importOpen by remember { mutableStateOf(false) }
    var importGroupId by remember { mutableStateOf<String?>(null) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var routeOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var traceDetailsRequest by remember { mutableIntStateOf(0) }
    var exportRequest by remember { mutableStateOf<ExportSelection?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Windows up to about 1100 px get a narrower config column with one-line rows.
    val compact = maxWidth < 1100.dp
    Row(Modifier.fillMaxSize().background(background)) {
        // Mockup 03: the sidebar is not a menu any more; rules, journal and settings open from the top-right buttons.
        // Mockup 03: folders and configs live in a permanent left column (the desktop twin of the phone drawer).
        DesktopWorkspaceSidebar(onOpenNetwork, width = if (compact) 248.dp else 316.dp) {
            if (tab != Tab.PROFILES) {
                WorkspaceNavigationItem("← Главная", false, { tab = Tab.PROFILES }) { NavSymbol(Tab.PROFILES, false) }
            }
            ConfigList(ui.saved, tunnel, ui.busy, ui.probes, controller, compact = compact,
                onImport = { groupId -> importGroupId = groupId; importOpen = true },
                onGroupRoutes = { groupId -> routeOwnerId = "grp_$groupId"; tab = Tab.ROUTES },
                onExportGroup = { group -> exportRequest = ExportSelection(group.id, "LerNET-${group.name.take(30)}.lernet.json") },
                onSelect = { id -> controller.select(id); tab = Tab.PROFILES },
            ) {
                Box {
                    GroupActionButton("Добавить конфиг или папку", onClick = { createMenu = true }) { color ->
                        Text("+", color = color, fontSize = 20.sp, lineHeight = 20.sp)
                    }
                    DropdownMenu(createMenu, onDismissRequest = { createMenu = false }) {
                        DropdownMenuItem(text = { Text("Импортировать конфиг") }, onClick = { importGroupId = null; importOpen = true; createMenu = false })
                        DropdownMenuItem(text = { Text("Импорт архива LerNET") }, onClick = {
                            createMenu = false
                            chooseTransferFile(false)?.let(controller::importFrom)
                        })
                        DropdownMenuItem(text = { Text("Создать папку") }, onClick = { newGroupOpen = true; createMenu = false })
                        DropdownMenuItem(text = { Text("Экспортировать всё") }, onClick = {
                            createMenu = false
                            exportRequest = ExportSelection(null, "LerNET-all.lernet.json")
                        })
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(22.dp)) {
            val holding = ui.tried != null && tunnel.status != TunnelStatus.STOPPED
            val statusLine = if (holding) "Нет связи · VPN держим" else (if (tunnel.status == TunnelStatus.RUNNING) ui.healthMessage else tunnel.message)
                .ifBlank { "Готово к подключению" }
            // The home and the journal already say the state in their own body: the header does not repeat it.
            // Other pages mention the state only when something needs attention.
            val headerStatus = tab != Tab.PROFILES && tab != Tab.DIAGNOSTICS &&
                (holding || tunnel.status == TunnelStatus.FAILED || ui.healthFailures >= 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tab.label, style = MaterialTheme.typography.headlineMedium)
                    if (headerStatus) Spacer(Modifier.height(4.dp))
                    if (headerStatus) Text(statusLine, style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            holding -> hold
                            ui.healthFailures >= 2 -> desktopColors.error
                            tunnel.status == TunnelStatus.RUNNING && ui.healthVerified != true -> Color(0xFFFFD27B)
                            else -> statusColor(tunnel.status)
                        })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Tab.ROUTES, Tab.DIAGNOSTICS, Tab.SETTINGS).forEach { item ->
                        val chosen = tab == item
                        OutlinedButton(
                            onClick = { if (item == Tab.ROUTES) routeOwnerId = null; tab = if (chosen) Tab.PROFILES else item },
                            colors = if (chosen) ButtonDefaults.outlinedButtonColors(containerColor = desktopColors.secondaryContainer)
                                else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.testTag("top_${item.name.lowercase()}"),
                        ) {
                            NavSymbol(item, chosen)
                            Spacer(Modifier.width(6.dp))
                            Text(item.label)
                        }
                    }
                }
            }
            val progress = ui.busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RECONNECTING)
            if (ui.message.isNotBlank() && ui.message != statusLine && ui.message !in plainStates && !(tab == Tab.PROFILES && progress))
                Text(ui.message, color = if (ui.connectionError != null) desktopColors.error else muted, maxLines = 2)
                if (ui.networkWarning.isNotBlank()) Text(ui.networkWarning, color = Color(0xFFF1CC80), fontSize = 12.sp, maxLines = 3)
            ui.guardProblem?.let { Text(it, color = desktopColors.error, fontSize = 12.sp, maxLines = 3) }
            Spacer(Modifier.height(18.dp))
            when (tab) {
                Tab.PROFILES -> ProfileHome(ui.saved, tunnel, ui.busy, ui.probes, ui.tunnelLatencyMs, trace, controller,
                    detail = homeDetail(ui, tunnel, holding),
                    onImport = { groupId -> importGroupId = groupId; importOpen = true }, onDiagnostics = { traceDetailsRequest++; tab = Tab.DIAGNOSTICS },
                    localProxy = ui.localProxy.takeIf { tunnel.status == TunnelStatus.RUNNING },
                    tried = ui.tried.takeIf { holding }, history = connectionHistory,
                    onOpenRules = { owner -> routeOwnerId = owner; tab = Tab.ROUTES },
                    onOpenJournal = { tab = Tab.DIAGNOSTICS })
                Tab.ROUTES -> DesktopRoutes(ui.saved, controller, routeOwnerId)
                Tab.DIAGNOSTICS -> Diagnostics(ui.saved, tunnel, diagnostics, connectionHistory, trace, traceDetailsRequest, controller)
                Tab.SETTINGS -> Settings(ui.saved, controller, onRequestElevation,
                    localProxy = ui.localProxy.takeIf { tunnel.status == TunnelStatus.RUNNING },
                    locked = ui.busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RECONNECTING))
            }
        }
    }
    }
    ui.connectionError?.let { error ->
        AlertDialog(
            onDismissRequest = controller::dismissConnectionError,
            title = { Text("Не удалось подключиться") },
            text = {
                SelectionContainer {
                    Text(error.message, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.dismissConnectionError()
                    if (error.routeOwnerId != null) {
                        routeOwnerId = error.routeOwnerId
                        tab = Tab.ROUTES
                    } else {
                        tab = Tab.DIAGNOSTICS
                    }
                }) { Text(if (error.routeOwnerId != null) "Открыть маршруты" else "Диагностика") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissConnectionError) { Text("Закрыть") }
            },
        )
    }
    if (importOpen) ImportDialog(
        groupName = ui.saved.groups.firstOrNull { it.id == importGroupId }?.name,
        onDismiss = { importOpen = false },
    ) { controller.import(it, importGroupId); importOpen = false }
    if (newGroupOpen) NameDialog("Новая папка", "", { newGroupOpen = false }) {
        controller.addGroup(it); newGroupOpen = false
    }
    exportRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { exportRequest = null },
            title = { Text("Экспорт настроек") },
            text = { Text("Архив содержит адреса серверов и ключи доступа к VPN. Сохраняйте и передавайте его только тем, кому доверяете.") },
            confirmButton = { TextButton(onClick = {
                exportRequest = null
                chooseTransferFile(true, request.suggestedName)?.let { controller.exportTo(it, request.groupId) }
            }) { Text("Сохранить архив") } },
            dismissButton = { TextButton(onClick = { exportRequest = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NavSymbol(tab: Tab, selected: Boolean) {
    val color = if (selected) desktopColors.primary else muted
    Canvas(Modifier.size(19.dp)) {
        val stroke = 1.8.dp.toPx()
        when (tab) {
            Tab.PROFILES -> {
                drawRoundRect(color, topLeft = Offset(size.width * .12f, size.height * .12f),
                    size = androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .65f),
                    cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
                drawLine(color, Offset(size.width * .3f, size.height * .86f), Offset(size.width * .78f, size.height * .86f), stroke)
            }
            Tab.ROUTES -> {
                drawLine(color, Offset(size.width * .24f, size.height * .2f), Offset(size.width * .7f, size.height * .38f), stroke)
                drawLine(color, Offset(size.width * .24f, size.height * .2f), Offset(size.width * .7f, size.height * .79f), stroke)
                listOf(Offset(.24f, .2f), Offset(.7f, .38f), Offset(.7f, .79f)).forEach { p ->
                    drawCircle(color, radius = 2.8.dp.toPx(), center = Offset(size.width * p.x, size.height * p.y))
                }
            }
            Tab.DIAGNOSTICS -> {
                drawLine(color, Offset(0f, size.height * .85f), Offset(size.width, size.height * .85f), stroke)
                drawRect(color, topLeft = Offset(size.width * .12f, size.height * .5f), size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height * .34f))
                drawRect(color, topLeft = Offset(size.width * .42f, size.height * .2f), size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height * .64f))
                drawRect(color, topLeft = Offset(size.width * .72f, size.height * .4f), size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height * .44f))
            }
            Tab.SETTINGS -> {
                drawLine(color, Offset(size.width * .12f, size.height * .3f), Offset(size.width * .88f, size.height * .3f), stroke)
                drawLine(color, Offset(size.width * .12f, size.height * .7f), Offset(size.width * .88f, size.height * .7f), stroke)
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width * .4f, size.height * .3f))
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width * .65f, size.height * .7f))
            }
        }
    }
}

@Composable
internal fun ConfigList(saved: StoredState, tunnel: TunnelSnapshot, busy: Boolean, probes: Map<String, ProbeResult>,
    controller: DesktopController, compact: Boolean = false, onImport: (String?) -> Unit, onGroupRoutes: (String) -> Unit,
    onExportGroup: (StoredGroup) -> Unit, onSelect: (String) -> Unit, addAction: @Composable () -> Unit) {
    val selectionLocked = busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RECONNECTING)
    var delete by remember { mutableStateOf<StoredProfile?>(null) }
    var renameGroup by remember { mutableStateOf<StoredGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<StoredGroup?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var moveMenu by remember { mutableStateOf(false) }
    var expanded by remember(saved.groups) { mutableStateOf(saved.groups.map { it.id }.toSet()) }
    val profileBounds = remember { mutableStateMapOf<String, Rect>() }
    val groupBounds = remember { mutableStateMapOf<String, Rect>() }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var draggingGroupId by remember { mutableStateOf<String?>(null) }
    var groupDragStart by remember { mutableStateOf(Offset.Zero) }
    var groupDragDelta by remember { mutableStateOf(Offset.Zero) }
    val hoverPoint = if (draggingId != null) dragStart + dragDelta else null
    val hoveredProfile = hoverPoint?.let { point -> profileBounds.entries.firstOrNull { it.key != draggingId && it.value.contains(point) }?.key }
    val hoveredGroup = if (hoveredProfile == null) hoverPoint?.let { point -> groupBounds.entries.firstOrNull { it.value.contains(point) }?.key } else null
    val hoveredRoot = hoveredProfile == null && hoveredGroup == null && hoverPoint?.let { rootBounds?.contains(it) } == true
    val groupHoverPoint = if (draggingGroupId != null) groupDragStart + groupDragDelta else null
    val hoveredGroupOrder = groupHoverPoint?.let { point -> groupBounds.entries.firstOrNull { it.key != draggingGroupId && it.value.contains(point) }?.key }
    val beginDrag: (String, Offset) -> Unit = { id, windowPoint ->
        draggingId = id
        dragStart = windowPoint
        dragDelta = Offset.Zero
    }
    val finishDrag: () -> Unit = {
        val id = draggingId
        if (id != null) {
            val point = dragStart + dragDelta
            val target = profileBounds.entries.firstOrNull { it.key != id && it.value.contains(point) }
            when {
                target != null -> {
                    val after = point.y > target.value.center.y
                    controller.dropProfile(id, target.key, null, after)
                    saved.profiles.firstOrNull { it.id == target.key }?.groupId?.let { expanded = expanded + it }
                }
                else -> {
                    val group = groupBounds.entries.firstOrNull { it.value.contains(point) }?.key
                    if (group != null) { controller.dropProfile(id, null, group, false); expanded = expanded + group }
                    else if (rootBounds?.contains(point) == true || listBounds?.contains(point) == true) controller.dropProfile(id, null, null, false)
                }
            }
        }
        draggingId = null
        dragDelta = Offset.Zero
    }
    val finishGroupDrag: () -> Unit = {
        val source = draggingGroupId
        val point = groupDragStart + groupDragDelta
        val target = groupBounds.entries.firstOrNull { it.key != source && it.value.contains(point) }
        if (source != null && target != null) controller.reorderGroup(source, target.key, point.y > target.value.center.y)
        draggingGroupId = null
        groupDragDelta = Offset.Zero
    }
    Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Конфиги · " + saved.profiles.size, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                addAction()
            }
            TextButton(onClick = { controller.probe(saved.profiles.map { it.id }) }, enabled = saved.profiles.isNotEmpty() && probes.values.none { it.checking },
                contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Проверить все", fontSize = 12.sp) }
            val known = saved.profiles.mapNotNull { probes[it.id] }
            if (known.isNotEmpty()) {
                val done = known.filter { !it.checking }
                val summary = "проверено ${done.size} из ${saved.profiles.size} · отвечают: ${done.count { it.latencyMs != null }}"
                Text(if (known.any { it.checking }) "Проверяем… · $summary" else summary.replaceFirstChar { it.uppercase() },
                    color = muted, fontSize = 11.sp)
            }
            if (selectionLocked) Text("Дождитесь завершения переключения", color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f).onGloballyPositioned { listBounds = it.boundsInWindow() }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                saved.groups.forEach { group ->
                    item("group-" + group.id) {
                        DisposableEffect(group.id) { onDispose { groupBounds.remove(group.id) } }
                        val groupFocus = remember(group.id) { FocusRequester() }
                        Card(
                            modifier = Modifier.fillMaxWidth().onGloballyPositioned { groupBounds[group.id] = it.boundsInWindow() }
                                .graphicsLayer { translationY = if (draggingGroupId == group.id) groupDragDelta.y else 0f
                                    shadowElevation = if (draggingGroupId == group.id) 20f else 0f }
                                .wholeCardDrag(group.id,
                                    onDragStart = { local ->
                                        draggingGroupId = group.id
                                        groupDragStart = (groupBounds[group.id]?.topLeft ?: Offset.Zero) + local
                                        groupDragDelta = Offset.Zero
                                    },
                                    onDrag = { groupDragDelta += it },
                                    onDragEnd = finishGroupDrag)
                                .focusRequester(groupFocus).focusable()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Delete, Key.Backspace)) {
                                        deleteGroup = group; true
                                    } else false
                                }
                                .clickable { groupFocus.requestFocus(); expanded = if (group.id in expanded) expanded - group.id else expanded + group.id },
                            colors = CardDefaults.cardColors(containerColor = if (draggingGroupId == group.id) Color(0xFF323D62)
                                else if (hoveredGroup == group.id || hoveredGroupOrder == group.id) Color(0xFF243C51) else panel),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FolderGlyph()
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                                        if (group.autoSwap) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("АВТО", color = green, fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                                    .background(green.copy(alpha = .12f))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    .semantics { contentDescription = "Автопереключение включено" })
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        FolderChevron(group.id in expanded)
                                    }
                                    Text(configCount(saved.profiles.count { it.groupId == group.id }),
                                        color = muted, fontSize = 11.sp, maxLines = 1)
                                    val groupResults = saved.profiles.filter { it.groupId == group.id }.mapNotNull { probes[it.id] }
                                    if (groupResults.isNotEmpty()) {
                                        Text("Отвечают: ${groupResults.count { it.latencyMs != null }} из ${saved.profiles.count { it.groupId == group.id }}",
                                            color = blue, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                GroupActionButton("Проверить серверы папки",
                                    onClick = { controller.probe(saved.profiles.filter { it.groupId == group.id }.map { it.id }) }) { color ->
                                    PingGlyph(color)
                                }
                                Spacer(Modifier.width(2.dp))
                                Box {
                                    GroupActionButton("Действия с папкой", onClick = { groupMenu = group.id }) { color ->
                                        Text("⋮", color = color, fontSize = 18.sp, lineHeight = 18.sp)
                                    }
                                    DropdownMenu(expanded = groupMenu == group.id, onDismissRequest = { groupMenu = null }) {
                                        DropdownMenuItem(text = { Text(if (group.autoSwap) "Выключить автосмену" else "Включить автосмену") },
                                            onClick = { controller.setGroupSwap(group.id, !group.autoSwap); groupMenu = null })
                                        DropdownMenuItem(text = { Text("Добавить конфиг сюда") }, onClick = { onImport(group.id); groupMenu = null })
                                        DropdownMenuItem(text = { Text("Маршруты папки") }, onClick = { onGroupRoutes(group.id); groupMenu = null })
                                        DropdownMenuItem(text = { Text("Экспортировать папку") }, onClick = { groupMenu = null; onExportGroup(group) })
                                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { renameGroup = group; groupMenu = null })
                                        DropdownMenuItem(text = { Text("Удалить папку") }, onClick = { deleteGroup = group; groupMenu = null })
                                    }
                                }
                            }
                        }
                    }
                    items(saved.profiles.filter { it.groupId == group.id && group.id in expanded }, key = { it.id }) { profile ->
                        DisposableEffect(profile.id) { onDispose { profileBounds.remove(profile.id) } }
                        ProfileRow(profile, profile.id == saved.selectedProfileId, !selectionLocked, probes[profile.id],
                            onProbe = { controller.probe(listOf(profile.id)) }, onMove = { controller.moveProfile(profile.id, it) },
                            onDuplicate = { controller.duplicateProfile(profile.id) },
                            onRefreshSubscription = profile.subscriptionUrl?.takeIf(String::isNotBlank)?.let { url -> { controller.refreshSubscription(url) } },
                            groups = saved.groups, onSetGroup = { destination ->
                                if (controller.setGroup(profile.id, destination) && destination != null) expanded = expanded + destination
                            },
                            dragging = draggingId == profile.id, dropTarget = hoveredProfile == profile.id, indent = true, dragOffset = dragDelta,
                            onBounds = { profileBounds[profile.id] = it },
                            onDragStart = { local -> beginDrag(profile.id, (profileBounds[profile.id]?.topLeft ?: Offset.Zero) + local) },
                            onDrag = { dragDelta += it }, onDragEnd = finishDrag,
                            onDelete = { delete = profile }, compact = compact) { onSelect(profile.id) }
                    }
                }
                items(saved.profiles.filter { it.groupId == null }, key = { it.id }) { profile ->
                    DisposableEffect(profile.id) { onDispose { profileBounds.remove(profile.id) } }
                    ProfileRow(profile, profile.id == saved.selectedProfileId, !selectionLocked, probes[profile.id],
                        onProbe = { controller.probe(listOf(profile.id)) }, onMove = { controller.moveProfile(profile.id, it) },
                        onDuplicate = { controller.duplicateProfile(profile.id) },
                        onRefreshSubscription = profile.subscriptionUrl?.takeIf(String::isNotBlank)?.let { url -> { controller.refreshSubscription(url) } },
                        groups = saved.groups, onSetGroup = { destination ->
                            if (controller.setGroup(profile.id, destination) && destination != null) expanded = expanded + destination
                        },
                        dragging = draggingId == profile.id, dropTarget = hoveredProfile == profile.id, indent = false, dragOffset = dragDelta,
                        onBounds = { profileBounds[profile.id] = it },
                        onDragStart = { local -> beginDrag(profile.id, (profileBounds[profile.id]?.topLeft ?: Offset.Zero) + local) },
                        onDrag = { dragDelta += it }, onDragEnd = finishDrag,
                        onDelete = { delete = profile }, compact = compact) { onSelect(profile.id) }
                }
                if (draggingId != null) item("drop-root") {
                    DisposableEffect(Unit) { onDispose { rootBounds = null } }
                    Surface(color = if (hoveredRoot) Color(0xFF243C51) else panel, shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (hoveredRoot) blue else desktopColors.outline),
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { rootBounds = it.boundsInWindow() }) {
                        Text("На верхний уровень", color = if (hoveredRoot) blue else muted, modifier = Modifier.padding(14.dp))
                    }
                }
            }
    }
    renameGroup?.let { group ->
        NameDialog("Название папки", group.name, { renameGroup = null }) {
            controller.renameGroup(group.id, it); renameGroup = null
        }
    }
    deleteGroup?.let { group ->
        val confirm = { controller.deleteGroup(group.id); deleteGroup = null }
        AlertDialog(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { confirm(); true }
                    Key.Escape -> { deleteGroup = null; true }
                    else -> false
                }
            },
            onDismissRequest = { deleteGroup = null },
            title = { Text("Удалить папку «${group.name}»?") },
            text = { Text("Конфиги останутся в общем списке. Правила папки будут удалены.") },
            confirmButton = { TextButton(onClick = confirm) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("Отмена") } },
        )
    }
    delete?.let { profile ->
        AlertDialog(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { controller.delete(profile.id); delete = null; true }
                    Key.Escape -> { delete = null; true }
                    else -> false
                }
            },
            onDismissRequest = { delete = null },
            title = { Text("Удалить «" + profile.name + "»?") },
            text = { Text("Конфиг и его правила будут удалены.") },
            confirmButton = {
                TextButton(onClick = { controller.delete(profile.id); delete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
internal fun ProfileHome(saved: StoredState, tunnel: TunnelSnapshot, busy: Boolean, probes: Map<String, ProbeResult>, tunnelLatencyMs: Long?, trace: TraceSnapshot,
    controller: DesktopController, onImport: (String?) -> Unit, onDiagnostics: () -> Unit,
    localProxy: LocalProxyAccess?, tried: FailoverReport?, history: List<LiveConnection>,
    onOpenRules: (String) -> Unit, onOpenJournal: () -> Unit, detail: String? = null) {
    val selected = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
    LaunchedEffect(selected?.id) { selected?.let { controller.probe(listOf(it.id)) } }
    var editProfile by remember { mutableStateOf<StoredProfile?>(null) }
    var delete by remember { mutableStateOf<StoredProfile?>(null) }
    var moveMenu by remember { mutableStateOf(false) }
    var showJson by remember(selected?.id) { mutableStateOf(false) }
    var showDetails by remember(selected?.id) { mutableStateOf(false) }
    var showCheckDetails by remember(selected?.id) { mutableStateOf(false) }
    if (selected == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 490.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.Black, shape = androidx.compose.foundation.shape.CircleShape) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Text("L", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(20.dp))
                Text("Конфигов пока нет", style = MaterialTheme.typography.headlineSmall, color = desktopColors.onBackground)
                Spacer(Modifier.height(8.dp))
                Text("Добавьте ссылку на сервер, файл конфига или подписку от вашего VPN-сервиса. После этого здесь появится кнопка подключения.", color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { onImport(null) }) { Text("+ Добавить конфиг") }
            }
        }
        return
    }
    val endpoint = remember(selected.selectedOutbound?.singBoxJson) {
        selected.selectedOutbound?.let { OutboundPatch.read(it.singBoxJson) }
    }
    val active = busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RUNNING, TunnelStatus.RECONNECTING)
    val holding = tried != null
    val proxyMode = controller.effectiveMode(selected) == RunMode.PROXY
    val group = saved.groups.firstOrNull { it.id == selected.groupId }
    val (statusTitle, statusTone) = when {
        holding -> "Нет связи · VPN держим" to hold
        tunnel.status == TunnelStatus.RUNNING -> "Подключено" to green
        active -> "Подключаемся…" to blue
        tunnel.status == TunnelStatus.FAILED -> "Не удалось подключиться" to desktopColors.error
        else -> "Отключено" to muted
    }
    val sideCards: @Composable ColumnScope.(Boolean) -> Unit = { underMain ->
        tried?.let { report ->
            Column {
                // Under the main card the buttons come first, so they stay on screen in a small window.
                if (underMain) {
                    HoldActions(saved, selected, controller)
                    Spacer(Modifier.height(10.dp))
                }
                FailoverReportCard(report)
                if (!underMain) HoldActions(saved, selected, controller)
            }
        }
        HomeRulesCard(saved, selected, onOpenRules)
        HomeRecentRequests(history, active, onOpenJournal)
        HomeSkippedConditions(saved, selected)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Narrow windows: the right cards go under the main card instead of squeezing it.
    val stacked = maxWidth < 760.dp
    val sideWidth = if (maxWidth < 980.dp) 300.dp else 340.dp
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Panel {
                    Column(Modifier.fillMaxWidth().testTag("home_center"), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Same order as the phone: button, the state once, then what a press will do.
                        ConnectionPower(active = active, busy = busy, holding = holding, connected = tunnel.status == TunnelStatus.RUNNING && !busy,
                            failed = tunnel.status == TunnelStatus.FAILED, onClick = {
                            if (active) controller.disconnect() else controller.connect()
                        })
                        Text(statusTitle, style = MaterialTheme.typography.titleLarge, color = statusTone, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("home_state"))
                        // Holding: «Выключить VPN» with its explanation is right next to it, so no second «Отключить» here.
                        // A failure keeps «Подключить»: on Windows there is no separate retry button.
                        if (!holding) Text(if (active) "Отключить" else "Подключить", color = muted, fontSize = 13.sp)
                        detail?.takeIf { it.isNotBlank() && it != statusTitle }?.let {
                            Text(it, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(selected.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        val meta = listOfNotNull(group?.let { "Папка «${it.name}»" + if (it.autoSwap) " · автосмена" else "" },
                            if (proxyMode) "режим прокси (меняется в Настройках)" else null).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = muted, fontSize = 13.sp, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    // Proxy address, login and password are in Settings; the home only names the mode.
                    // While LerNET holds the tunnel the report card says what was tried; the checks would only repeat it.
                    if (!holding) {
                    Spacer(Modifier.height(2.dp))
                    // Off or failed: the tiles show the config's own last check, not the live connection.
                    if (tunnel.status != TunnelStatus.RUNNING && probes[selected.id]?.let { !it.checking } == true) {
                        Text("Последняя проверка конфига", color = muted, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val probe = probes[selected.id]
                        ConnectionLatency("До сервера",
                            status = when {
                                probe?.latencyMs != null -> "Отвечает"
                                probe?.checking == true -> "Проверяем…"
                                probe != null -> "Нет ответа"
                                else -> "Не проверено"
                            },
                            value = probe?.latencyMs?.let { "$it мс" } ?: "—",
                            healthy = probe?.latencyMs != null,
                            modifier = Modifier.weight(1f))
                        val channelLatency = if (active && tunnelLatencyMs != null) tunnelLatencyMs else probe?.tunnelLatencyMs
                        ConnectionLatency("Интернет через VPN", status = when {
                            channelLatency != null -> "Работает"
                            probe?.checking == true -> "Проверяем…"
                            !probe?.tunnelMessage.isNullOrBlank() -> "Не работает"
                            else -> "Не проверено"
                        }, value = channelLatency?.let { "$it мс" } ?: "—",
                            healthy = channelLatency != null, modifier = Modifier.weight(1f))
                    }
                    probes[selected.id]?.let { probe ->
                        if (!probe.checking && probe.tunnelLatencyMs == null && probe.tunnelMessage.isNotBlank()) {
                            Text(app.lernet.engine.text.UserText.clean(probe.tunnelMessage, "Через этот конфиг интернет не открывается."),
                                color = desktopColors.error, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Technical words live here, folded away, like «Подробнее» on the phone.
                    TextButton(onClick = { showCheckDetails = !showCheckDetails }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (showCheckDetails) "Скрыть подробности  ▴" else "Подробнее  ▾", fontSize = 12.sp)
                    }
                    if (showCheckDetails) {
                        val probe = probes[selected.id]
                        val address = endpoint?.let { if (it.port.isBlank()) it.server else "${it.server}:${it.port}" }.orEmpty()
                        Text(listOfNotNull(
                            "Протокол: ${selected.selectedOutbound?.type?.uppercase() ?: "—"}" + (address.takeIf { it.isNotBlank() }?.let { " · сервер $it" } ?: ""),
                            "«До сервера» — ${probe?.serverProtocol ?: EndpointProbe.protocol(selected)}-соединение с сервером, без VPN.",
                            "«Интернет через VPN» — HTTP-запрос через временный локальный прокси этого конфига.",
                            "Сетевой адаптер VPN (TUN) создаётся и проверяется только при подключении.",
                            probe?.tunnelMessage?.takeIf { probe.tunnelLatencyMs == null }?.let { app.lernet.engine.text.UserText.clean(it, "") }?.takeIf { it.isNotBlank() }?.let { "Ответ проверки: $it" },
                        ).joinToString("\n"), color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { controller.probe(listOf(selected.id)) }, enabled = probes[selected.id]?.checking != true) {
                            Text(if (probes[selected.id]?.checking == true) "Проверяем…" else "Проверить связь")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { editProfile = selected }) { Text("Изменить") }
                        TextButton(onClick = { delete = selected }) { Text("Удалить") }
                    }
                    }
                }
                if (stacked) {
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { sideCards(true) }
                }
                Spacer(Modifier.height(14.dp))
                HopJourney(
                    trace = if (trace.target.equals(endpoint?.server, ignoreCase = true)) trace else TraceSnapshot(),
                    server = endpoint?.server.orEmpty(),
                    runningTunnel = tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RUNNING, TunnelStatus.RECONNECTING),
                    onTrace = controller::traceSelected,
                    onDetails = onDiagnostics,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Скрыть настройки конфига  ▴" else "Настройки конфига  ▾")
                }
                if (showDetails) {
                Spacer(Modifier.height(14.dp))
                Panel {
                    Text("Подключение", fontWeight = FontWeight.SemiBold)
                    ProfileDetail("Сервер", endpoint?.let {
                        if (it.port.isBlank()) it.server else "${it.server}:${it.port}"
                    }.orEmpty().ifBlank { "Не указан" })
                    ProfileDetail("Транспорт", endpoint?.transportType?.uppercase()?.ifBlank { "По умолчанию" } ?: "По умолчанию")
                    ProfileDetail("SNI", endpoint?.sni?.ifBlank { "Не указан" } ?: "Не указан")
                    ProfileDetail("Режим", if (controller.effectiveMode(selected) == RunMode.PROXY) "Локальный прокси" else "Полный VPN")
                    ProfileDetail("DNS", when (selected.dnsPolicy) {
                        "SYSTEM" -> "Глобальная настройка"
                        "PROFILE" -> "Из конфига"
                        "TUNNEL" -> "Через VPN"
                        else -> "DNS сети (мимо VPN)"
                    })
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Папка: " + (saved.groups.firstOrNull { it.id == selected.groupId }?.name ?: "Без папки"), color = muted, modifier = Modifier.weight(1f), maxLines = 1)
                    Box {
                        OutlinedButton(onClick = { moveMenu = true }) { Text("Переместить") }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            DropdownMenuItem(text = { Text("Без папки") }, onClick = { controller.setGroup(selected.id, null); moveMenu = false })
                            saved.groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) }, onClick = {
                                controller.setGroup(selected.id, group.id)
                                moveMenu = false
                            }) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val preview = remember(selected, saved.rules, saved.mode, saved.defaultDnsPolicy, saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer, saved.strictRoute) {
                    runCatching { controller.preview(selected.id) }.getOrNull()
                }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Итоговая конфигурация", fontWeight = FontWeight.SemiBold)
                            Text("JSON для ядра sing-box · содержит ключи доступа", color = muted, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { preview?.json?.let(::copyToClipboard) },
                            enabled = preview?.isValid == true,
                        ) { Text("Копировать") }
                    }
                    if (preview == null || !preview.isValid) {
                        Text(preview?.errors?.joinToString("; ") ?: "Не удалось собрать конфигурацию", color = Color(0xFFFF9F9F))
                    } else {
                        TextButton(onClick = { showJson = !showJson }) {
                            Text(if (showJson) "Скрыть JSON" else "Показать JSON")
                        }
                        if (showJson) {
                            SelectionContainer {
                                Text(
                                    preview.json, fontSize = 11.sp, color = muted,
                                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
                }
        }
        if (!stacked) Column(Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) { sideCards(false) }
    }
    }
    editProfile?.let { profile -> DesktopProfileEditor(profile, saved, controller) { editProfile = null } }
    delete?.let { profile ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Удалить «" + profile.name + "»?") },
            text = { Text("Конфиг и его правила будут удалены.") },
            confirmButton = { TextButton(onClick = { controller.delete(profile.id); delete = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } },
        )
    }
}

/** Words that only name the state; the home already shows the state under the button. */
private val plainStates = setOf("Отключено", "Подключено", "Подключаемся…", "Готово к подключению")

/** One extra line under the state on the home, only when it says something the state itself does not. */
private fun homeDetail(ui: DesktopUiState, tunnel: TunnelSnapshot, holding: Boolean): String? = when {
    holding -> null
    ui.busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RECONNECTING) ->
        ui.message.takeIf { it.isNotBlank() && it !in plainStates } ?: tunnel.message.takeIf { it !in plainStates }
    tunnel.status == TunnelStatus.RUNNING && (ui.healthVerified != true || ui.healthFailures > 0) -> ui.healthMessage
    tunnel.status == TunnelStatus.FAILED -> tunnel.message
    else -> null
}?.let { app.lernet.engine.text.UserText.clean(it, "") }?.takeIf { it.isNotBlank() }

internal fun tunnelStatusText(status: TunnelStatus): String = when (status) {
    TunnelStatus.STOPPED -> "Отключено"
    TunnelStatus.STARTING -> "Подключаемся…"
    TunnelStatus.RUNNING -> "Подключено"
    TunnelStatus.RECONNECTING -> "Переподключаемся…"
    TunnelStatus.FAILED -> "Не удалось подключиться"
}

internal fun configCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "конфигов"
        n % 10 == 1 -> "конфиг"
        n % 10 in 2..4 -> "конфига"
        else -> "конфигов"
    }
    return "$n $word"
}

private fun routeOwner(profile: StoredProfile): String = profile.groupId?.let { "grp_$it" } ?: profile.id

/** Mockup 10: while LerNET holds the tunnel the person can pick a config from another folder or switch VPN off. */
@Composable
private fun HoldActions(saved: StoredState, selected: StoredProfile, controller: DesktopController) {
    var other by remember { mutableStateOf(false) }
    val candidates = saved.profiles.filter { it.groupId != selected.groupId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { other = true }, enabled = candidates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Конфиг из другой папки", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            }
            DropdownMenu(other, onDismissRequest = { other = false }) {
                candidates.forEach { profile ->
                    val folder = saved.groups.firstOrNull { it.id == profile.groupId }?.name ?: "без папки"
                    DropdownMenuItem(text = { Text("${profile.name}  ·  $folder", maxLines = 1) },
                        onClick = { other = false; controller.select(profile.id) })
                }
            }
        }
        OutlinedButton(onClick = controller::disconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Выключить VPN", maxLines = 1, fontSize = 12.sp)
        }
    }
    Text("Выключить VPN можно только вручную. Тогда трафик пойдёт напрямую, и LerNET покажет это.",
        color = muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun HomeChip(text: String, tone: Color) {
    Text(text, color = tone, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(tone.copy(alpha = .13f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun HomeCardHeader(title: String, action: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(action, fontSize = 12.sp) }
    }
}

/** Top-level rules of the folder (or config) that is selected; the editor itself stays on the «Правила» page. */
@Composable
private fun HomeRulesCard(saved: StoredState, selected: StoredProfile, onOpenRules: (String) -> Unit) {
    val owner = routeOwner(selected)
    val top = saved.rules.filter { it.profileId == owner && it.parentId == null }.sortedWith(compareBy<StoredRule> { it.sortIndex }.thenBy { it.id })
    val skipped = remember(saved.rules, owner) { DesktopRouteTree.platformInactiveIds(saved.rules.filter { it.profileId == owner }) }
    Panel(Modifier.testTag("home_rules")) {
        HomeCardHeader("Правила", "Открыть →") { onOpenRules(owner) }
        if (top.isEmpty()) Text("Своих правил нет: весь трафик идёт через выбранный конфиг.", color = muted, fontSize = 12.sp)
        top.take(6).forEach { rule ->
            val children = saved.rules.count { it.parentId == rule.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.title.ifBlank { "Без названия" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp, color = if (rule.enabled) desktopColors.onSurface else muted)
                Spacer(Modifier.width(6.dp))
                when {
                    !rule.enabled -> HomeChip("выключено", muted)
                    rule.id in skipped -> HomeChip("пропущено", hold)
                    children > 0 -> HomeChip("ветка · $children", blue)
                    rule.action == "DIRECT" -> HomeChip("напрямую", green)
                    rule.action == "BLOCK" -> HomeChip("запрет · только в Эксперте", desktopColors.error)
                    else -> HomeChip("через VPN", blue)
                }
            }
        }
        if (top.size > 6) Text("и ещё ${top.size - 6}", color = muted, fontSize = 12.sp)
    }
}

/** Real requests from the core's connection list; nothing is shown before the first connection. */
@Composable
private fun HomeRecentRequests(history: List<LiveConnection>, active: Boolean, onOpenJournal: () -> Unit) {
    val recent = history.sortedByDescending { it.lastSeenAt }.take(6)
    Panel(Modifier.testTag("home_recent")) {
        HomeCardHeader("Последние запросы", "Журнал →", onOpenJournal)
        if (recent.isEmpty()) Text(if (active) "Пока запросов не было." else "Появятся после подключения.", color = muted, fontSize = 12.sp)
        recent.forEach { item ->
            val route = item.chains.firstOrNull().orEmpty().lowercase()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(item.process.substringAfterLast('\\').ifBlank { "—" }, item.host.ifBlank { item.destination }).joinToString(" · "),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                when {
                    "block" in route || "reject" in route -> HomeChip("запрет", desktopColors.error)
                    "direct" in route -> HomeChip("напрямую", green)
                    else -> HomeChip("через VPN", blue)
                }
            }
        }
    }
}

/** Mockup 10: a branch that depends on Android apps cannot match here; say so instead of silently skipping it. */
@Composable
private fun HomeSkippedConditions(saved: StoredState, selected: StoredProfile) {
    val owner = routeOwner(selected)
    val rules = saved.rules.filter { it.profileId == owner }
    val inactive = remember(rules) { DesktopRouteTree.platformInactiveIds(rules) }
    val top = rules.filter { it.id in inactive && it.parentId !in inactive }
    if (top.isEmpty()) return
    Panel(Modifier.testTag("skipped_conditions")) {
        Text("Пропущенные условия", fontWeight = FontWeight.SemiBold)
        top.take(5).forEach { rule ->
            Text("«${rule.title.ifBlank { "Без названия" }}»: условие для Android-приложений в Windows не работает",
                fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (top.size > 5) Text("и ещё ${top.size - 5}", color = muted, fontSize = 12.sp)
        Text("Такие ветки пропускаются на этом компьютере вместе с вложенными правилами; на Android они работают.",
            color = muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}


@Composable
private fun SegmentedChoice(choices: List<Pair<String, String>>, selected: String, enabled: Boolean = true,
    onSelect: (String) -> Unit) {
    Surface(color = Color(0xFF0C1421), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, desktopColors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { (value, label) ->
                val isSelected = value == selected
                val fill by animateColorAsState(if (isSelected) desktopColors.primaryContainer else Color.Transparent,
                    animationSpec = tween(180), label = "selection-fill")
                val interactions = remember { MutableInteractionSource() }
                Surface(color = fill, shape = RoundedCornerShape(9.dp), modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .selectable(isSelected, enabled = enabled, role = Role.RadioButton,
                        interactionSource = interactions, indication = null, onClick = { onSelect(value) })) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Box(Modifier.size(7.dp).background(if (isSelected) blue else desktopColors.outline, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) desktopColors.onPrimaryContainer else muted,
                            maxLines = 2, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPower(active: Boolean, busy: Boolean, holding: Boolean = false, connected: Boolean = active, failed: Boolean = false, onClick: () -> Unit) {
    // Green only once connected; connecting stays blue and a failure is red, like on the phone.
    val accent by animateColorAsState(if (holding) hold else if (connected) green else if (failed && !active) desktopColors.error else blue, tween(250), label = "power-color")
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .90f else if (hovered) .955f else 1f, tween(160), label = "power-depth")
    val elevation by animateDpAsState(if (pressed) 0.dp else if (hovered) 2.dp else 9.dp, tween(160), label = "power-shadow")
    val glow by animateFloatAsState(if (pressed) .12f else if (hovered) .20f else .30f, tween(180), label = "power-glow")
    Column(Modifier.width(130.dp).hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (active) "Отключить VPN" else "Подключить VPN" },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).graphicsLayer {
            scaleX = scale; scaleY = scale
            translationY = if (pressed) 3.dp.toPx() else if (hovered) 1.dp.toPx() else 0f
            shadowElevation = elevation.toPx(); shape = CircleShape
        }.clip(CircleShape)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = glow), Color(0xFF152238), Color(0xFF0D1522)))),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(accent.copy(alpha = .17f), radius = size.minDimension * .47f, center = center,
                    style = Stroke(1.dp.toPx()))
                drawCircle(accent.copy(alpha = if (hovered) .42f else .65f), radius = size.minDimension * .39f, center = center,
                    style = Stroke(2.6.dp.toPx()))
                val radius = 16.dp.toPx()
                drawArc(accent, startAngle = -45f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawLine(accent, Offset(center.x, center.y - 22.dp.toPx()), Offset(center.x, center.y + 3.dp.toPx()),
                    strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ConnectionLatency(label: String, status: String, value: String, healthy: Boolean,
    modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF101A29), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (healthy) green.copy(alpha = .16f) else desktopColors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(status, color = if (healthy) green else if (status == "Нет ответа" || status == "Не работает")
                desktopColors.error else muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = if (healthy) Color(0xFF80DEBE) else desktopColors.onSurface,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProfileDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = muted, fontSize = 12.sp, modifier = Modifier.width(90.dp))
        Text(value, color = desktopColors.onSurface, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Modifier.wholeCardDrag(key: String, onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit): Modifier {
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    return pointerInput(key) {
        detectDragGestures(onDragStart = { start(it) }, onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
            change.consume()
            drag(amount)
        }
    }
}

@Composable
private fun GroupActionButton(label: String, onClick: () -> Unit,
    icon: @Composable (Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            pressed -> blue.copy(alpha = .18f)
            hovered -> blue.copy(alpha = .11f)
            else -> Color.Transparent
        },
        label = "folder-action-fill",
    )
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(fill)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics {
            contentDescription = label
        }, contentAlignment = Alignment.Center) {
        icon(if (hovered) blue else muted)
    }
}

@Composable
private fun PingGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        val left = size.width * .33f
        val right = size.width * .67f
        val top = size.height * .19f
        val bottom = size.height * .81f
        val wing = size.width * .14f
        drawLine(color, Offset(left, bottom), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(left - wing, top + wing), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(left + wing, top + wing), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right - wing, bottom - wing), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right + wing, bottom - wing), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun FolderChevron(expanded: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.6.dp.toPx()
        if (expanded) {
            drawLine(muted, Offset(size.width * .25f, size.height * .38f),
                Offset(size.width * .5f, size.height * .62f), stroke, cap = StrokeCap.Round)
            drawLine(muted, Offset(size.width * .5f, size.height * .62f),
                Offset(size.width * .75f, size.height * .38f), stroke, cap = StrokeCap.Round)
        } else {
            drawLine(muted, Offset(size.width * .38f, size.height * .25f),
                Offset(size.width * .62f, size.height * .5f), stroke, cap = StrokeCap.Round)
            drawLine(muted, Offset(size.width * .62f, size.height * .5f),
                Offset(size.width * .38f, size.height * .75f), stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun FolderGlyph() {
    Canvas(Modifier.size(24.dp)) {
        drawRoundRect(blue, topLeft = Offset(size.width * .08f, size.height * .32f),
            size = androidx.compose.ui.geometry.Size(size.width * .84f, size.height * .58f),
            cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(blue, topLeft = Offset(size.width * .08f, size.height * .18f),
            size = androidx.compose.ui.geometry.Size(size.width * .39f, size.height * .3f),
            cornerRadius = CornerRadius(2.dp.toPx()))
    }
}

@Composable
private fun ProfileGlyph() {
    Canvas(Modifier.size(21.dp)) {
        drawRoundRect(blue, topLeft = Offset(size.width * .12f, size.height * .1f),
            size = androidx.compose.ui.geometry.Size(size.width * .76f, size.height * .62f),
            cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.7.dp.toPx()))
        drawLine(blue, Offset(size.width * .5f, size.height * .72f), Offset(size.width * .5f, size.height * .88f), 1.5.dp.toPx())
    }
}

@Composable
private fun ProfileRow(profile: StoredProfile, selected: Boolean, enabled: Boolean, probe: ProbeResult?, onProbe: () -> Unit,
    onMove: (Int) -> Unit, onDuplicate: () -> Unit, groups: List<StoredGroup>, onSetGroup: (String?) -> Unit,
    onRefreshSubscription: (() -> Unit)? = null,
    dragging: Boolean, dropTarget: Boolean, indent: Boolean, dragOffset: Offset,
    onBounds: (Rect) -> Unit, onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
    onDelete: () -> Unit, compact: Boolean = false, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = if (indent) 14.dp else 0.dp).onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .graphicsLayer { translationY = if (dragging) dragOffset.y else 0f; shadowElevation = if (dragging) 20f else 0f }
            .wholeCardDrag(profile.id, onDragStart, onDrag, onDragEnd)
            .clip(RoundedCornerShape(13.dp)).focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Delete, Key.Backspace)) {
                    onDelete(); true
                } else false
            }
            .clickable(enabled = enabled && !dragging) { focus.requestFocus(); onClick() },
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (selected) blue.copy(alpha = .42f) else if (dropTarget) green else desktopColors.outlineVariant.copy(alpha = .65f)),
        colors = CardDefaults.cardColors(containerColor = if (dragging) Color(0xFF323D62) else if (dropTarget) Color(0xFF243C51)
            else if (selected) Color(0xFF20304B) else panel),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = if (compact) 6.dp else 10.dp, bottom = if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileGlyph()
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    if (compact) {
                        // One short line under the name instead of two metric columns: the name keeps the full width.
                        val (word, tone) = when {
                            probe?.checking == true -> "Проверяем…" to muted
                            probe?.tunnelLatencyMs != null -> "Работает · ${probe.tunnelLatencyMs} мс" to green
                            probe?.latencyMs != null && probe.tunnelMessage.isBlank() -> "Сервер отвечает · ${probe.latencyMs} мс" to muted
                            probe?.latencyMs != null -> "Через VPN не работает" to desktopColors.error
                            probe != null -> "Сервер не отвечает" to desktopColors.error
                            else -> "" to muted
                        }
                        if (word.isNotEmpty()) Text(word, color = tone, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.size(28.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("⋮", fontSize = 19.sp)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Выше") }, onClick = { onMove(-1); menu = false })
                        DropdownMenuItem(text = { Text("Ниже") }, onClick = { onMove(1); menu = false })
                        DropdownMenuItem(text = { Text("Переместить в папку…") }, onClick = { menu = false; folderMenu = true })
                        DropdownMenuItem(text = { Text("Дублировать") }, onClick = { onDuplicate(); menu = false })
                        // Your choice, renames and edits are kept; the change takes effect at the next connect.
                        onRefreshSubscription?.let { refresh ->
                            DropdownMenuItem(text = { Text("Обновить подписку") }, onClick = { refresh(); menu = false })
                        }
                    }
                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text("Без папки") }, onClick = { onSetGroup(null); folderMenu = false })
                        groups.forEach { group ->
                            DropdownMenuItem(text = { Text(group.name) }, onClick = { onSetGroup(group.id); folderMenu = false })
                        }
                    }
                }
            }
            if (!compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProbeMiniMetric("До сервера",
                    when {
                        probe?.latencyMs != null -> "Отвечает"
                        probe?.checking == true -> "Проверяем…"
                        probe != null -> "Нет ответа"
                        else -> "Не проверено"
                    }, probe?.latencyMs?.let { "$it мс" } ?: "—", probe?.latencyMs != null, Modifier.weight(1f))
                ProbeMiniMetric("Через VPN", when {
                    probe?.tunnelLatencyMs != null -> "Работает"
                    probe?.checking == true -> "Проверяем…"
                    !probe?.tunnelMessage.isNullOrBlank() -> "Не работает"
                    else -> "Не проверено"
                }, probe?.tunnelLatencyMs?.let { "$it мс" } ?: "—",
                    probe?.tunnelLatencyMs != null, Modifier.weight(1f))
                TextButton(onClick = onProbe, enabled = probe?.checking != true,
                    modifier = Modifier.size(28.dp).semantics { contentDescription = "Проверить связь" },
                    contentPadding = PaddingValues(0.dp)) { PingGlyph(blue) }
            }
        }
    }
}

@Composable
private fun ProbeMiniMetric(label: String, status: String, value: String, healthy: Boolean,
    modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = muted, fontSize = 10.sp, maxLines = 1)
        Text(status, color = if (healthy) green else if (status == "Нет ответа" || status == "Не работает")
            desktopColors.error else muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = if (healthy) green else muted,
            fontWeight = FontWeight.Medium, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable
private fun Diagnostics(saved: StoredState, tunnel: TunnelSnapshot, diagnostics: CoreDiagnostics,
    history: List<LiveConnection>, trace: TraceSnapshot, traceDetailsRequest: Int, controller: DesktopController) {
    var display by remember { mutableStateOf("live") }
    var selected by remember { mutableStateOf<LiveConnection?>(null) }
    val activeProfile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
    Column {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ТЕКУЩИЙ КОНФИГ", color = blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(activeProfile?.name ?: "Конфиг не выбран", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val groupName = saved.groups.firstOrNull { it.id == activeProfile?.groupId }?.name
                    Text(listOfNotNull(groupName, activeProfile?.selectedOutbound?.type?.uppercase()).joinToString(" · ").ifBlank { "Выберите конфиг на главном экране" }, color = muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        TraceView(trace, traceDetailsRequest, onRefresh = controller::traceSelected)
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelMetric("Состояние", tunnel.message.ifBlank { tunnelStatusText(tunnel.status) }, statusColor(tunnel.status), Modifier.weight(1f))
            PanelMetric("Отправлено", bytesText(diagnostics.uploadTotal), desktopColors.onSurface, Modifier.weight(1f))
            PanelMetric("Получено", bytesText(diagnostics.downloadTotal), desktopColors.onSurface, Modifier.weight(1f))
            PanelMetric("Активные соединения", diagnostics.connections.size.toString(), desktopColors.onSurface, Modifier.weight(1f))
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Диагностика", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(display == "live", onClick = { display = "live" }, label = { Text("Сейчас") })
            Spacer(Modifier.width(7.dp))
            FilterChip(display == "history", onClick = { display = "history" }, label = { Text("История · ${history.size}") })
            Spacer(Modifier.width(7.dp))
            FilterChip(display == "log", onClick = { display = "log" }, label = { Text("Технический журнал") })
        }
        if (diagnostics.error.isNotBlank() && tunnel.status == TunnelStatus.RUNNING) {
            Text(diagnostics.error, color = desktopColors.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (display == "log") {
            Card(colors = CardDefaults.cardColors(containerColor = panel)) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(tunnel.logs.reversed()) { line -> Text(line, color = muted, fontSize = 12.sp) }
                    }
                }
            }
        } else {
            val rows = if (display == "history") history else diagnostics.connections.sortedByDescending { it.started }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rows.isEmpty()) {
                    item { Panel { Text(if (display == "history") "За этот запуск соединений пока нет" else if (tunnel.status == TunnelStatus.RUNNING) "Активных соединений сейчас нет" else "Подключите конфиг, чтобы видеть соединения", color = muted) } }
                }
                items(rows, key = { it.id }) { row ->
                    val process = remember(row.process) { WindowsProcessIdentity.resolve(row.process) }
                    Card(modifier = Modifier.fillMaxWidth().clickable { selected = row }, colors = CardDefaults.cardColors(containerColor = panel)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            process.icon?.let { icon ->
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(row.host.ifBlank { row.destination }, color = desktopColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(if (row.active) "●" else "○", process.label, row.network, row.rule).filter(String::isNotBlank).joinToString(" · "), color = muted, fontSize = 12.sp, maxLines = 1)
                            }
                            Text("↑ ${bytesText(row.upload)}  ↓ ${bytesText(row.download)}", color = muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(row.host.ifBlank { row.destination }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Адрес: ${row.destination}")
                    val process = remember(row.process) { WindowsProcessIdentity.resolve(row.process) }
                    Text("Приложение: ${process.label}")
                    process.parent?.let { Text("Родительский процесс: $it") }
                    if (row.process.isNotBlank()) Text("Путь: ${row.process}")
                    Text("Протокол: ${row.network.ifBlank { "не передан ядром" }}")
                    Text("Правило: ${row.rule.ifBlank { "не передано ядром" }}")
                    Text("Цепочка: ${row.chains.joinToString(" → ").ifBlank { "нет данных" }}")
                    Text("Трафик: ↑ ${bytesText(row.upload)} · ↓ ${bytesText(row.download)}")
                    Text("HTTP-заголовки и тело зашифрованных соединений ядро не раскрывает.", color = muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun HopJourney(trace: TraceSnapshot, server: String, runningTunnel: Boolean, onTrace: () -> Unit, onDetails: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(trace.hops.size, trace.running) {
        if (trace.running) { delay(100); scroll.animateScrollTo(scroll.maxValue) }
    }
    val reached = trace.message.startsWith("Маршрут до сервера найден") || trace.hops.any { it.ip == server }
    Panel(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Путь до сервера", fontWeight = FontWeight.SemiBold)
                Text(server.ifBlank { "Адрес появится после выбора конфига" }, color = muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onTrace, enabled = server.isNotBlank() && !trace.running) {
                Text(if (trace.running) "Проверяем…" else "Проверить путь")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val requiredWidth = 144.dp + (trace.hops.size * 68).dp + ((trace.hops.size + 1) * 28).dp
            val routeWidth = maxOf(maxWidth, requiredWidth)
            val overflow = requiredWidth > maxWidth
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    Row(Modifier.width(routeWidth).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        JourneyEndpoint("Устройство", false, blue)
                        JourneyConnector(Modifier.weight(1f), blue)
                        trace.hops.forEach { hop ->
                            key(hop.number) {
                                Column(Modifier.width(68.dp).height(86.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Surface(color = if (hop.ip == null) Color(0xFF1C2533) else Color(0xFF20334B),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, if (hop.ip == null) desktopColors.outlineVariant else Color(0xFF324B68)),
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onDetails)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            if (hop.country != null) {
                                                CountryFlagIcon(hop.country)
                                                Text(hop.country.uppercase(), color = desktopColors.onSurface, fontSize = 10.sp, lineHeight = 15.sp)
                                            } else Text(if (hop.ip == null) "···" else "${hop.number}",
                                                color = if (hop.ip == null) muted else blue, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    Text("${hop.number} · ${hop.rttMs?.let { "$it мс" } ?: "—"}", color = muted,
                                        fontSize = 10.sp, maxLines = 1)
                                }
                                JourneyConnector(Modifier.weight(1f), if (hop.ip == null) desktopColors.outline else blue)
                            }
                        }
                        JourneyEndpoint("Сервер", true, if (reached || runningTunnel) green else blue)
                    }
                }
                if (overflow) {
                    HorizontalScrollbar(adapter = rememberScrollbarAdapter(scroll), modifier = Modifier.fillMaxWidth().height(7.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("${trace.hops.size} узлов · прокрутите маршрут по горизонтали", color = muted, fontSize = 11.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trace.running) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
            else Box(Modifier.size(6.dp).background(if (reached) green else desktopColors.outline, CircleShape))
            Text(trace.message.ifBlank { "Покажет узлы между устройством и VPN-сервером." }, color = muted, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
        }
        if (trace.hops.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("···  Узел не отвечает на трассировку", color = muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDetails) { Text("Все узлы  ↗") }
            }
        }
    }
}

@Composable
private fun JourneyEndpoint(label: String, server: Boolean, tone: Color) {
    Column(Modifier.width(72.dp).height(86.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(color = tone.copy(alpha = .13f), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, tone.copy(alpha = .20f))) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (server) Text("VPN", color = tone, fontSize = 13.sp, fontWeight = FontWeight.Bold) else ProfileGlyph()
            }
        }
        Text(label, color = muted, fontSize = 11.sp)
    }
}

@Composable
private fun JourneyConnector(modifier: Modifier, tone: Color) {
    Canvas(modifier.height(52.dp)) {
        val y = size.height / 2f
        val inset = 3.dp.toPx()
        drawLine(tone.copy(alpha = .42f), Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
        drawLine(tone.copy(alpha = .65f), Offset(size.width - inset - 4.dp.toPx(), y - 3.dp.toPx()),
            Offset(size.width - inset, y), 1.3.dp.toPx())
        drawLine(tone.copy(alpha = .65f), Offset(size.width - inset - 4.dp.toPx(), y + 3.dp.toPx()),
            Offset(size.width - inset, y), 1.3.dp.toPx())
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceView(trace: TraceSnapshot, detailsRequest: Int, onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(detailsRequest) { if (detailsRequest > 0) expanded = true }
    Card(colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Путь до сервера", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (trace.running) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                TextButton(onClick = onRefresh) { Text("Проверить путь") }
                TextButton(onClick = { expanded = !expanded }, enabled = trace.hops.isNotEmpty()) { Text(if (expanded) "Свернуть" else "Подробности") }
            }
            Text(trace.message.ifBlank { "Путь проверяется только по кнопке «Проверить путь»: проверка идёт мимо VPN. Подключаться для этого не нужно." }, color = muted, fontSize = 12.sp)
            if (trace.hops.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Компьютер", color = desktopColors.onSurface, fontSize = 12.sp)
                    trace.hops.forEach { hop ->
                        Text("→", color = muted, fontSize = 12.sp)
                        Surface(color = if (hop.ip == null) Color(0xFF343845) else Color(0xFF264059), shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (hop.country != null) CountryFlagIcon(hop.country)
                                Text(if (hop.ip == null) "*" else hop.country?.uppercase() ?: "?", color = desktopColors.onSurface, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (expanded) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        trace.hops.forEach { hop ->
                            val facts = listOfNotNull(
                                hop.ip,
                                hop.ptr?.takeIf { it != hop.ip },
                                hop.country?.uppercase(),
                                hop.asn?.let { "AS$it" },
                                hop.holder,
                                hop.registeredTo?.takeIf { it != hop.holder },
                                hop.prefix,
                                hop.rttMs?.let { "$it мс" },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                hop.country?.let { CountryFlagIcon(it) }
                                Text("${hop.number}. " + facts.joinToString(" · ").ifBlank { "Нет ответа на ICMP" },
                                    color = if (hop.ip == null) muted else desktopColors.onSurface, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryFlagIcon(code: String) {
    val path = remember(code) {
        val normalized = code.lowercase()
        if (normalized.length == 2 && normalized.all(Char::isLetter) &&
            Thread.currentThread().contextClassLoader.getResource("flags/4x3/$normalized.png") != null) {
            "flags/4x3/$normalized.png"
        } else null
    }
    if (path != null) {
        Image(painter = painterResource(path), contentDescription = "Флаг ${code.uppercase()}",
            modifier = Modifier.size(width = 23.dp, height = 17.dp).clip(RoundedCornerShape(2.dp)), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PanelMetric(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, color = muted, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun bytesText(value: Long): String = when {
    value >= 1_073_741_824 -> "%.1f ГБ".format(value / 1_073_741_824.0)
    value >= 1_048_576 -> "%.1f МБ".format(value / 1_048_576.0)
    value >= 1024 -> "%.1f КБ".format(value / 1024.0)
    else -> "$value Б"
}

@Composable
internal fun Settings(saved: StoredState, controller: DesktopController, onRequestElevation: () -> Unit,
    localProxy: LocalProxyAccess? = null, locked: Boolean = false) {
    var mtu by remember(saved.tunMtu) { mutableStateOf(saved.tunMtu.toString()) }
    var xmuxMin by remember(saved.xmuxConcurrency) { mutableStateOf(saved.xmuxConcurrency.substringBefore('-')) }
    var xmuxMax by remember(saved.xmuxConcurrency) { mutableStateOf(saved.xmuxConcurrency.substringAfter('-')) }
    var directDns by remember(saved.directDnsServer) { mutableStateOf(saved.directDnsServer) }
    var dnsPolicy by remember(saved.defaultDnsPolicy) { mutableStateOf(saved.defaultDnsPolicy) }
    var logLevel by remember(saved.logLevel) { mutableStateOf(saved.logLevel) }
    var healthUrl by remember(saved.healthUrl) { mutableStateOf(saved.healthUrl) }
    var journalMb by remember(saved.journalMaxMb) { mutableStateOf(saved.journalMaxMb.toString()) }
    val mtuValid = mtu.toIntOrNull()?.let { it in 1280..9000 } == true
    val minimum = xmuxMin.toIntOrNull()
    val maximum = xmuxMax.toIntOrNull()
    val xmuxValid = minimum != null && maximum != null && minimum > 0 && maximum >= minimum
    val dnsValid = EngineDefaults.validIpv4(directDns.trim())
    var advanced by remember { mutableStateOf(false) }
    val defaultsChanged = mtu != saved.tunMtu.toString() || "$xmuxMin-$xmuxMax" != saved.xmuxConcurrency ||
        directDns.trim() != saved.directDnsServer || dnsPolicy != saved.defaultDnsPolicy || logLevel != saved.logLevel
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.testTag("settings_run_mode")) { SettingsRunMode(saved, controller, onRequestElevation, localProxy, locked) }
        Panel { DesktopUpdateCard() }
        Panel {
            SettingHeading(SecurityTexts.PANEL_TAG, SecurityTexts.PANEL_TITLE, SecurityTexts.PANEL_BODY)
            SecurityToggle(SecurityTexts.CRASH_GUARD, SecurityTexts.CRASH_GUARD_HINT, saved.crashGuard, controller::setCrashGuard)
            SecurityToggle(SecurityTexts.STRICT_ROUTE, SecurityTexts.STRICT_ROUTE_HINT, saved.strictRoute, controller::setStrictRoute)
            Text(SecurityTexts.APPLIES_ON_RECONNECT, color = muted, fontSize = 12.sp)
        }
        Panel { ProxyPasswordSettings(saved, controller) }
        Panel {
            Text("Параметры подключения", style = MaterialTheme.typography.titleLarge)
            Text("Общие значения для конфигов. Если конфиг содержит собственную настройку, она имеет приоритет. Изменения вступят в силу при следующем подключении.",
                color = muted, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = desktopColors.outlineVariant)
            SettingHeading("DNS", "Кто определяет адреса сайтов",
                "DNS переводит имя сайта в IP-адрес. Выберите источник настройки рядом с адресом сервера.")
            Box(Modifier.fillMaxWidth()) {
                SegmentedChoice(listOf("TUNNEL" to "Через VPN", "UNDERLAY" to "Напрямую", "PROFILE" to "Из конфига"), dnsPolicy) { dnsPolicy = it }
            }
            OutlinedTextField(directDns, { directDns = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (dnsPolicy == "PROFILE") "Резервный DNS · IPv4" else "DNS-сервер · IPv4") },
                placeholder = { Text("1.1.1.1") }, isError = !dnsValid, singleLine = true,
                supportingText = { Text(if (!dnsValid) "Введите один IPv4-адрес, например 1.1.1.1." else "Один IPv4-адрес. Список через запятую здесь не поддерживается.") })
            Text(if (dnsPolicy == "PROFILE")
                "Если в конфиге есть DNS, используются его серверы и правила. Импортированный JSON может содержать несколько серверов; их выбор задаёт конфигурация. Если DNS в конфиге отсутствует, используется адрес выше. Он также нужен для прямых DNS-зависимостей."
            else if (dnsPolicy == "TUNNEL") "По умолчанию. Адреса сайтов запрашиваются через VPN в зашифрованном виде, поэтому сеть не видит, какие сайты вы открываете. Напрямую идёт только запрос адреса самого VPN-сервера. Если на каком-то сервере так не работает (бывает, когда хостинг сервера режет DNS), выберите для этого конфига «DNS сети»."
            else "Для DNS используется адрес, заданный здесь, через прямое подключение мимо VPN: сеть и провайдер видят имена сайтов. Несколько DNS можно задать в JSON конфига и выбрать «Из конфига».",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = {
                    controller.setDefaultDnsPolicy(DnsPolicy.valueOf(dnsPolicy))
                    controller.setDefaults(mtu.toInt(), "$xmuxMin-$xmuxMax", directDns.trim(), logLevel)
                }, enabled = defaultsChanged && mtuValid && xmuxValid && dnsValid) { Text("Сохранить параметры") }
                Text(if (defaultsChanged) "Есть несохранённые изменения" else "Параметры сохранены", color = muted, fontSize = 12.sp)
            }
        }
        // Same as on Android: rarely needed knobs are folded into «Дополнительно», closed by default.
        Panel {
            Row(
                Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("settings_advanced"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Дополнительно", style = MaterialTheme.typography.titleLarge)
                    Text("MTU, XMUX, журнал, рукопожатие TLS, адрес проверки связи",
                        color = muted, fontSize = 12.sp)
                }
                FolderChevron(advanced)
            }
        }
        if (advanced) Panel {
            SettingHeading("MTU", "Размер пакета VPN",
                "Максимальный размер IP-пакета в виртуальном сетевом адаптере, в байтах. Влияет только на режим VPN.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(mtu, { mtu = it.filter(Char::isDigit).take(4) }, modifier = Modifier.width(160.dp),
                    label = { Text("MTU, байт") }, isError = !mtuValid, singleLine = true,
                    supportingText = { Text(if (mtuValid) "1280–9000" else "Допустимо 1280–9000") })
                TextButton(onClick = { mtu = "1500" }) { Text("По умолчанию: 1500") }
            }
            Text("Больше — меньше накладных расходов, но слишком крупные пакеты могут теряться или дробиться. Меньше — проще пройти сеть с ограничениями, но больше пакетов и нагрузка.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            SettingAdvice("Как подобрать", "Начните с 1500. Если сайты или загрузки зависают, сравните 1400 и 1280 на тех же задачах после переподключения. Оставьте наибольшее значение со стабильной работой. Универсальной формулы для любого VPN нет; обычный пинг не измеряет MTU всего пути.")
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = desktopColors.outlineVariant)
            SettingHeading("XMUX", "Потоки в одном соединении XHTTP",
                "Несколько запросов делят одно соединение с сервером. Диапазон ниже задаёт, сколько запросов оно может обслуживать одновременно.")
            Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF101A29), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, desktopColors.outlineVariant)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(xmuxMin, { xmuxMin = it.filter(Char::isDigit).take(10) }, modifier = Modifier.width(150.dp),
                            label = { Text("От · минимум") }, isError = !xmuxValid, singleLine = true)
                        Text("—", color = muted)
                        OutlinedTextField(xmuxMax, { xmuxMax = it.filter(Char::isDigit).take(10) }, modifier = Modifier.width(150.dp),
                            label = { Text("До · максимум") }, isError = !xmuxValid, singleLine = true)
                        TextButton(onClick = { xmuxMin = "16"; xmuxMax = "16" }) { Text("16–16") }
                    }
                    Text(if (!xmuxValid) "Нужны два целых числа больше нуля. Минимум не должен превышать максимум."
                        else if (minimum == maximum) "$minimum–$maximum: фиксированный предел $minimum запросов на соединение."
                        else "$minimum–$maximum: ядро выбирает предел из этого диапазона для нового соединения.",
                        color = if (xmuxValid) blue else desktopColors.error, fontSize = 12.sp)
                }
            }
            Text("Первое число — нижняя граница предела, второе — верхняя. Это не число загрузок и отдач. При достижении предела создаётся ещё одно соединение. Настройка применяется только к XHTTP, если конфиг не задаёт собственный XMUX.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            SettingAdvice("Как подобрать", "16–16 — исходное значение LerNET. Больший предел уменьшает число новых соединений, но больше запросов зависят от одного канала. Меньший создаёт больше соединений и увеличивает расходы на их установку. Сравнивайте стабильность, задержку и скорость при одинаковой нагрузке; больше не всегда быстрее.")
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = desktopColors.outlineVariant)
            Text("Подробность журнала", fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth()) {
                SegmentedChoice(listOf("debug" to "debug", "info" to "info", "warn" to "warn", "error" to "error"), logLevel) { logLevel = it }
            }
            Text(when (logLevel) {
                "debug" -> "Подробная диагностика для поиска ошибки. Создаёт больше записей."
                "warn" -> "Только предупреждения и ошибки; меньше подробностей о подключении."
                "error" -> "Только ошибки; успешные события подключения не записываются."
                else -> "Основные события, предупреждения и ошибки. Подходит для повседневной работы."
            }, color = muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = {
                    controller.setDefaultDnsPolicy(DnsPolicy.valueOf(dnsPolicy))
                    controller.setDefaults(mtu.toInt(), "$xmuxMin-$xmuxMax", directDns.trim(), logLevel)
                }, enabled = defaultsChanged && mtuValid && xmuxValid && dnsValid) { Text("Сохранить параметры") }
                Text(if (defaultsChanged) "Есть несохранённые изменения" else "Параметры сохранены", color = muted, fontSize = 12.sp)
            }
        }
        if (advanced) Panel {
            SettingHeading("TLS", "Рукопожатие TLS",
                "Для сетей, где фильтр режет или теряет первое сообщение защищённого соединения. Действует для узлов VLESS, VMess, Trojan с TLS или Reality. Значения из самого конфига важнее. Вступит в силу при следующем подключении.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Дробить первое сообщение TLS", Modifier.weight(1f))
                Switch(saved.tlsRecordFragment, onCheckedChange = {
                    controller.setTlsHandshake(it, app.lernet.engine.compile.RealityKeyShare.fromStorage(saved.realityKeyShare))
                })
            }
            Text("Разбивает приветствие на несколько записей TLS (record_fragment). Помогает, если соединение рвётся или молчит сразу после начала рукопожатия. Немного замедляет подключение. По умолчанию выключено.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            Text("Обмен ключами Reality", fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth()) {
                SegmentedChoice(listOf("" to "Как в отпечатке", "hybrid" to "Гибридный", "classical" to "Короткий"),
                    app.lernet.engine.compile.RealityKeyShare.fromStorage(saved.realityKeyShare).storage) {
                    controller.setTlsHandshake(saved.tlsRecordFragment, app.lernet.engine.compile.RealityKeyShare.fromStorage(it))
                }
            }
            Text("«Короткий» убирает большой постквантовый ключ из приветствия — оно меньше и реже теряется. Работает только с серверами Xray старше 26.9.8; с новыми серверами связь не установится.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (advanced) Panel {
            SettingHeading("HTTPS", "Проверка работоспособности",
                "Через этот адрес проверяется интернет через выбранный конфиг: перед подключением и во время работы VPN.")
            OutlinedTextField(healthUrl, { healthUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Адрес проверки") },
                singleLine = true, isError = !healthUrl.startsWith("https://"))
            Text("Приложение проверяет путь через VPN только по поводу: после подключения, после смены сети и когда трафик уходит, а ответов нет. Проверка идёт в уже открытом соединении, без особого заголовка. После неудачи повторяет её через случайные 3–8 секунд. Две неудачи подряд запускают переподключение или смену сервера внутри папки, если она включена. Используйте небольшой HTTPS-ресурс, доступный через VPN.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            Button(onClick = { controller.setHealthUrl(healthUrl) }, enabled = healthUrl.startsWith("https://") && healthUrl != saved.healthUrl) { Text("Сохранить адрес") }
        }
        if (advanced) Panel {
            SettingHeading("ЛОГИ", "Хранение журнала", "Логи хранятся в папке данных LerNET. Указанный объём делится между двумя файлами журнала.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(journalMb, { journalMb = it.filter(Char::isDigit).take(3) }, modifier = Modifier.width(160.dp),
                    label = { Text("Лимит, МБ") }, singleLine = true,
                    isError = journalMb.toIntOrNull()?.let { it in 1..500 } != true)
                Button(onClick = { controller.setJournalMaxMb(journalMb.toInt()) },
                    enabled = journalMb.toIntOrNull()?.let { it in 1..500 && it != saved.journalMaxMb } == true) { Text("Сохранить") }
            }
        }
        Panel {
            SettingHeading("ЗАПУСК", "Автозапуск", "LerNET сам возвращает VPN после перезагрузки компьютера, как и на телефоне.")
            SecurityToggle("Запускать при входе в систему",
                "LerNET запустится в трее и, если VPN был включён, сам подключит его снова. Кнопка «Отключить» отменяет это до следующего подключения.",
                saved.autostart, controller::setAutostart)
        }
        if (advanced) Panel {
            // Only the bundled, hash-checked core runs: the leak guard lets exactly this file through Windows.
            SettingHeading("ЯДРО", "sing-box-lx " + WindowsBoxProcess.PINNED_CORE_VERSION,
                "LerNET работает только со своим встроенным ядром: оно проверено и одинаково на Android и Windows.")
        }
    }
}

/** v2: the VPN/proxy choice moved off the home screen; full VPN stays the default. */
@Composable
internal fun SettingsRunMode(saved: StoredState, controller: DesktopController, onRequestElevation: () -> Unit,
    localProxy: LocalProxyAccess?, locked: Boolean) {
    val selected = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
    SettingHeading("РЕЖИМ", "Режим работы",
        "Полный VPN (по умолчанию) направляет весь трафик устройства через VPN. Прокси работает только для приложений, настроенных на 127.0.0.1:2080 с логином и паролем.")
    SegmentedChoice(
        listOf(RunMode.FULL_VPN.name to "VPN · весь трафик", RunMode.PROXY.name to "Локальный прокси"),
        saved.mode, enabled = !locked,
    ) { value ->
        if (value == RunMode.FULL_VPN.name && !WindowsElevation.isElevated) {
            if (controller.prepareVpnElevation(null)) onRequestElevation()
        } else controller.switchMode(null, RunMode.valueOf(value))
    }
    Text(
        when {
            saved.mode == RunMode.FULL_VPN.name && !WindowsElevation.isElevated ->
                "Для полного VPN нужны права администратора: Windows спросит их при выборе VPN или при запуске. Если доступ не дан, LerNET работает как локальный прокси."
            saved.mode == RunMode.FULL_VPN.name -> "Права администратора подтверждены. Трафик устройства идёт по правилам LerNET."
            else -> "LerNET запускается без прав администратора. Системный трафик идёт напрямую, через VPN идут только приложения с этим прокси."
        },
        color = muted, fontSize = 12.sp, lineHeight = 18.sp,
    )
    if (locked) Text("Дождитесь завершения переключения.", color = blue, fontSize = 12.sp)
    if (selected?.modeOverride != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("У конфига «${selected.name}» свой режим: " +
                (if (controller.effectiveMode(selected) == RunMode.PROXY) "локальный прокси" else "полный VPN"),
                color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.clearModeOverride(selected.id) }, enabled = !locked) { Text("Как для всех") }
        }
    }
    if (controller.effectiveMode(selected) == RunMode.PROXY) {
        if (localProxy != null) LocalProxyCard(localProxy)
        else Text("Адрес, логин и пароль прокси появятся здесь после подключения. Логин и пароль можно задать ниже.",
            color = muted, fontSize = 12.sp)
    }
    Text("Режим применяется при следующем подключении.", color = muted, fontSize = 11.sp)
}

@Composable
private fun LocalProxyCard(access: LocalProxyAccess) {
    Surface(color = desktopColors.background, shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, desktopColors.outline), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SelectionContainer(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ProxyLine(SecurityTexts.PROXY_ADDRESS, access.address)
                    ProxyLine(SecurityTexts.PROXY_LOGIN, access.username)
                    ProxyLine(SecurityTexts.PROXY_PASSWORD, access.password)
                }
            }
            OutlinedButton(onClick = { copyToClipboard(access.url()) }) { Text(SecurityTexts.COPY) }
        }
    }
}

@Composable
private fun ProxyLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun ProxyPasswordSettings(saved: StoredState, controller: DesktopController) {
    var user by remember(saved.proxyUsername) { mutableStateOf(saved.proxyUsername) }
    var password by remember(saved.proxyPassword) { mutableStateOf(saved.proxyPassword) }
    val clearing = user.isBlank() && password.isEmpty()
    val valid = clearing || (LocalProxyAccess.valid(user.trim()) && LocalProxyAccess.valid(password))
    val changed = user.trim() != saved.proxyUsername || password != saved.proxyPassword
    SettingHeading(SecurityTexts.PROXY_TAG, SecurityTexts.PROXY_TITLE, SecurityTexts.PROXY_BODY)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(user, { user = it.take(64) }, modifier = Modifier.width(220.dp), singleLine = true,
            label = { Text(SecurityTexts.PROXY_LOGIN) }, isError = user.isNotEmpty() && !LocalProxyAccess.valid(user.trim()))
        OutlinedTextField(password, { password = it.take(64) }, modifier = Modifier.width(220.dp), singleLine = true,
            label = { Text(SecurityTexts.PROXY_PASSWORD) }, isError = password.isNotEmpty() && !LocalProxyAccess.valid(password),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(onClick = { controller.setProxyCredentials(user, password) }, enabled = valid && changed) { Text(SecurityTexts.SAVE) }
    }
    Text(SecurityTexts.PROXY_RULES, color = muted, fontSize = 12.sp)
}

@Composable
private fun SecurityToggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun SettingHeading(tag: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = blue.copy(alpha = .12f), shape = RoundedCornerShape(6.dp)) {
                Text(tag, color = blue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Text(description, color = muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SettingAdvice(title: String, text: String) {
    Surface(color = Color(0xFF19283A), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
@Composable
private fun ImportDialog(groupName: String?, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (groupName == null) "Импорт конфига" else "Импорт в папку «$groupName»") },
        text = {
            Column {
                Text("Ссылка на сервер (vless://…), JSON-конфиг, архив LerNET или адрес подписки", color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextButton(onClick = {
                        value = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }
                            .getOrElse { error = "Не удалось прочитать буфер обмена"; value }
                    }) { Text("Вставить") }
                    TextButton(onClick = {
                        val picker = FileDialog(null as Frame?, "Выбрать конфигурацию", FileDialog.LOAD)
                        picker.isVisible = true
                        picker.file?.let { name ->
                            val file = Path.of(picker.directory, name)
                            runCatching {
                                check(Files.size(file) <= 20_000_000) { "Файл больше 20 МБ" }
                                Files.readString(file)
                            }.onSuccess { value = it; error = "" }
                                .onFailure { error = app.lernet.engine.text.UserText.of(it, "Не удалось прочитать файл") }
                        }
                        picker.dispose()
                    }) { Text("Из файла") }
                }
                OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth().height(170.dp), label = { Text("Конфигурация") })
                if (error.isNotBlank()) Text(error, color = desktopColors.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onImport(value.trim()) }, enabled = value.isNotBlank()) { Text("Импортировать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Название") }) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, desktopColors.outlineVariant.copy(alpha = .75f)),
        colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun statusColor(status: TunnelStatus): Color = when (status) {
    TunnelStatus.RUNNING -> Color(0xFF76D7A0)
    TunnelStatus.FAILED -> Color(0xFFFF8989)
    TunnelStatus.STARTING, TunnelStatus.RECONNECTING -> Color(0xFFFFD77A)
    TunnelStatus.STOPPED -> muted
}


private fun copyToClipboard(value: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
}

private fun chooseTransferFile(save: Boolean, suggestedName: String = ""): Path? {
    val dialog = FileDialog(null as Frame?, if (save) "Экспорт архива LerNET" else "Импорт архива LerNET",
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) dialog.file = suggestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    dialog.isVisible = true
    val result = dialog.file?.let { Path.of(dialog.directory, it) }
    dialog.dispose()
    return result
}


/** Mockup 10 E: which configs were tried, why, and when the next attempt is. Traffic does not leak meanwhile. */
@Composable
internal fun FailoverReportCard(report: FailoverReport) {
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    Surface(
        color = Color(0xFF1A1F2B), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF3A3324)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("failover_report"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(report.groupName?.let { "Все конфиги папки «$it» недоступны" } ?: "Все конфиги папки недоступны", fontWeight = FontWeight.SemiBold)
            Text("Что пробовали", color = muted, fontSize = 12.sp)
            report.attempts.forEach { attempt ->
                // Name and time on one line, the reason in full under it: a side card is too narrow for three columns.
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(desktopColors.error, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(attempt.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            java.time.Instant.ofEpochMilli(attempt.atMs).atZone(java.time.ZoneId.systemDefault()).format(time),
                            color = muted, fontSize = 12.sp,
                        )
                    }
                    Text(attempt.reason, Modifier.padding(start = 15.dp), color = muted, fontSize = 12.sp)
                }
            }
            Text(
                (report.nextRetry?.let { "Следующая попытка через $it. " } ?: "Пробуем снова автоматически. ") + "Маршруты VPN остаются на месте — трафик не уходит в обход.",
                color = muted, fontSize = 12.sp,
            )
        }
    }
}
