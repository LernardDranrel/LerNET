package app.lernet.desktop

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
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
private const val APP_VERSION = "1.0.0"
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
    var startupFallbackMessage: String? = null
    if (startupCheck == null && System.getProperty("os.name").startsWith("Windows")) {
        val saved = runCatching { DesktopStore().load() }.getOrDefault(StoredState())
        if (DesktopStartup.needsElevation(saved, WindowsElevation.isElevated)) {
            val elevation = WindowsElevation.relaunchAsAdministrator()
            if (elevation.isSuccess) return
            startupFallbackMessage = "Не удалось запустить VPN от имени администратора. Доступен локальный прокси: " +
                (elevation.exceptionOrNull()?.message ?: "запрос прав отменён")
        }
    }
    desktopApplication(startupCheck, startupFallbackMessage)
}

private fun desktopApplication(startupCheck: String?, startupFallbackMessage: String?) = application {
    val controller = remember { DesktopController().also { startupFallbackMessage?.let(it::showMessage) } }
    var visible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    val tunnel by controller.tunnel.state.collectAsState()
    val ui by controller.state.collectAsState()
    val icon = painterResource(
        when (tunnel.status) {
            TunnelStatus.RUNNING -> when {
                ui.healthFailures >= 2 -> "lernet-icon-failed.png"
                ui.healthVerified == true -> "lernet-icon-running.png"
                else -> "lernet-icon.png"
            }
            TunnelStatus.FAILED -> "lernet-icon-failed.png"
            else -> "lernet-icon.png"
        },
    )
    Tray(
        icon = icon,
        tooltip = "LerNET",
        onAction = { visible = true },
        menu = {
            Item("Открыть", onClick = { visible = true })
            Item("Отключить VPN", onClick = controller::disconnect)
            Separator()
            Item("Выход", onClick = { controller.close(); exitApplication() })
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
                DesktopScreen(controller, onRequestElevation = {
                    WindowsElevation.relaunchAsAdministrator()
                        .onSuccess { controller.close(); exitApplication() }
                        .onFailure { controller.showMessage(it.message ?: "Не удалось запросить права администратора") }
                })
            }
        }
    }
}

private enum class Tab(val label: String) {
    PROFILES("Профили"), ROUTES("Маршруты"), DIAGNOSTICS("Диагностика"), SETTINGS("Настройки")
}
private data class ExportSelection(val groupId: String?, val suggestedName: String)

@Composable
private fun DesktopScreen(controller: DesktopController, onRequestElevation: () -> Unit) {
    val ui by controller.state.collectAsState()
    val tunnel by controller.tunnel.state.collectAsState()
    val diagnostics by controller.diagnostics.collectAsState()
    val connectionHistory by controller.connectionHistory.collectAsState()
    val trace by controller.tracer.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.PROFILES) }
    var importOpen by remember { mutableStateOf(false) }
    var importGroupId by remember { mutableStateOf<String?>(null) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var routeOwnerId by remember { mutableStateOf<String?>(null) }
    var traceDetailsRequest by remember { mutableIntStateOf(0) }
    var exportRequest by remember { mutableStateOf<ExportSelection?>(null) }
    Row(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.width(196.dp).fillMaxHeight().background(Color(0xFF111925)).padding(horizontal = 14.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text("L", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("LerNET", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = desktopColors.onSurface)
                    Text("для Windows", color = muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(35.dp))
            Tab.entries.forEach { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                        .background(if (tab == item) desktopColors.primaryContainer else Color.Transparent)
                        .clickable { if (item == Tab.ROUTES) routeOwnerId = null; tab = item }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavSymbol(item, tab == item)
                    Spacer(Modifier.width(11.dp))
                    Text(item.label, color = if (tab == item) desktopColors.onSurface else muted,
                        fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = desktopColors.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text("LerNET $APP_VERSION", color = muted, fontSize = 11.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(22.dp)) {
            val statusLine = (if (tunnel.status == TunnelStatus.RUNNING) ui.healthMessage else tunnel.message)
                .ifBlank { "Готово к подключению" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tab.label, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(statusLine, style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            ui.healthFailures >= 2 -> desktopColors.error
                            tunnel.status == TunnelStatus.RUNNING && ui.healthVerified != true -> Color(0xFFFFD27B)
                            else -> statusColor(tunnel.status)
                        })
                }
                if (tab == Tab.PROFILES) {
                    Box {
                        Button(onClick = { createMenu = true }) { Text("+ Добавить") }
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
            if (tunnel.status != TunnelStatus.RUNNING && ui.message.isNotBlank() && ui.message != statusLine)
                Text(ui.message, color = muted, maxLines = 2)
            Spacer(Modifier.height(18.dp))
            when (tab) {
                Tab.PROFILES -> Profiles(ui.saved, tunnel, ui.busy, ui.probes, ui.tunnelLatencyMs, trace, controller,
                    onRequestElevation = onRequestElevation,
                    onImport = { groupId -> importGroupId = groupId; importOpen = true }, onDiagnostics = { traceDetailsRequest++; tab = Tab.DIAGNOSTICS },
                    onGroupRoutes = { groupId -> routeOwnerId = "grp_$groupId"; tab = Tab.ROUTES },
                    onExportGroup = { group -> exportRequest = ExportSelection(group.id, "LerNET-${group.name.take(30)}.lernet.json") })
                Tab.ROUTES -> DesktopRoutes(ui.saved, controller, routeOwnerId)
                Tab.DIAGNOSTICS -> Diagnostics(ui.saved, tunnel, diagnostics, connectionHistory, trace, traceDetailsRequest, controller)
                Tab.SETTINGS -> Settings(ui.saved, controller)
            }
        }
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
private fun Profiles(saved: StoredState, tunnel: TunnelSnapshot, busy: Boolean, probes: Map<String, ProbeResult>, tunnelLatencyMs: Long?, trace: TraceSnapshot,
    controller: DesktopController, onRequestElevation: () -> Unit, onImport: (String?) -> Unit, onDiagnostics: () -> Unit, onGroupRoutes: (String) -> Unit,
    onExportGroup: (StoredGroup) -> Unit) {
    val selected = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
    LaunchedEffect(selected?.id) { selected?.let { controller.probe(listOf(it.id)) } }
    val selectionLocked = busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RECONNECTING)
    var editProfile by remember { mutableStateOf<StoredProfile?>(null) }
    var delete by remember { mutableStateOf<StoredProfile?>(null) }
    var renameGroup by remember { mutableStateOf<StoredGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<StoredGroup?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var moveMenu by remember { mutableStateOf(false) }
    var showJson by remember(selected?.id) { mutableStateOf(false) }
    var showDetails by remember(selected?.id) { mutableStateOf(false) }
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(294.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Профили · " + saved.profiles.size, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.probe(saved.profiles.map { it.id }) }, enabled = saved.profiles.isNotEmpty() && probes.values.none { it.checking }) { Text("Проверить все") }
            }
            Text("Сервер TCP/TLS  ·  Канал HTTP", color = muted, fontSize = 11.sp)
            if (probes.isNotEmpty()) {
                val completed = probes.values.count { !it.checking }
                Text("Проверено $completed/${saved.profiles.size} · канал доступен: ${probes.values.count { it.tunnelLatencyMs != null }}",
                    color = muted, fontSize = 10.sp)
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
                                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text("${saved.profiles.count { it.groupId == group.id }} конфигов" + if (group.autoSwap) " · автопереключение ⇄" else "",
                                        color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val groupResults = saved.profiles.filter { it.groupId == group.id }.mapNotNull { probes[it.id] }
                                    if (groupResults.isNotEmpty()) {
                                        Text("Сервер ${groupResults.count { it.latencyMs != null }} · канал ${groupResults.count { it.tunnelLatencyMs != null }}",
                                            color = blue, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                FolderChevron(group.id in expanded)
                                TextButton(onClick = { controller.probe(saved.profiles.filter { it.groupId == group.id }.map { it.id }) },
                                    modifier = Modifier.size(30.dp), contentPadding = PaddingValues(0.dp)) { Text("↻", fontSize = 19.sp) }
                                DragHandle(onDragStart = { point ->
                                    draggingGroupId = group.id; groupDragStart = point; groupDragDelta = Offset.Zero
                                }, onDrag = { groupDragDelta += it }, onDragEnd = finishGroupDrag)
                                Box {
                                    TextButton(onClick = { groupMenu = group.id }, modifier = Modifier.size(28.dp),
                                        contentPadding = PaddingValues(0.dp)) { Text("⋮", fontSize = 19.sp) }
                                    DropdownMenu(expanded = groupMenu == group.id, onDismissRequest = { groupMenu = null }) {
                                        DropdownMenuItem(text = { Text("Добавить конфиг сюда") }, onClick = { onImport(group.id); groupMenu = null })
                                        DropdownMenuItem(text = { Text("Маршруты папки") }, onClick = { onGroupRoutes(group.id); groupMenu = null })
                                        DropdownMenuItem(text = { Text("Экспортировать папку") }, onClick = { groupMenu = null; onExportGroup(group) })
                                        DropdownMenuItem(text = { Text(if (group.autoSwap) "Выключить автопереключение" else "Включить автопереключение") }, onClick = { controller.setGroupSwap(group.id, !group.autoSwap); groupMenu = null })
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
                            groups = saved.groups, onSetGroup = { destination ->
                                if (controller.setGroup(profile.id, destination) && destination != null) expanded = expanded + destination
                            },
                            dragging = draggingId == profile.id, dropTarget = hoveredProfile == profile.id, indent = true, dragOffset = dragDelta,
                            onBounds = { profileBounds[profile.id] = it }, onDragStart = { local -> beginDrag(profile.id, local) },
                            onDrag = { dragDelta += it }, onDragEnd = finishDrag,
                            onDelete = { delete = profile }) { controller.select(profile.id) }
                    }
                }
                items(saved.profiles.filter { it.groupId == null }, key = { it.id }) { profile ->
                    DisposableEffect(profile.id) { onDispose { profileBounds.remove(profile.id) } }
                    ProfileRow(profile, profile.id == saved.selectedProfileId, !selectionLocked, probes[profile.id],
                        onProbe = { controller.probe(listOf(profile.id)) }, onMove = { controller.moveProfile(profile.id, it) },
                        onDuplicate = { controller.duplicateProfile(profile.id) },
                        groups = saved.groups, onSetGroup = { destination ->
                            if (controller.setGroup(profile.id, destination) && destination != null) expanded = expanded + destination
                        },
                        dragging = draggingId == profile.id, dropTarget = hoveredProfile == profile.id, indent = false, dragOffset = dragDelta,
                        onBounds = { profileBounds[profile.id] = it }, onDragStart = { local -> beginDrag(profile.id, local) },
                        onDrag = { dragDelta += it }, onDragEnd = finishDrag,
                        onDelete = { delete = profile }) { controller.select(profile.id) }
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
        Column(Modifier.weight(1f).fillMaxHeight().then(if (selected != null) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            if (selected == null) {
                Panel { HomeModeSelector(saved, null, selectionLocked, controller, onRequestElevation) }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 490.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = Color.Black, shape = androidx.compose.foundation.shape.CircleShape) {
                            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Text("L", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Ваши подключения — в одном месте", style = MaterialTheme.typography.headlineSmall, color = desktopColors.onBackground)
                        Spacer(Modifier.height(8.dp))
                        Text("Добавьте VLESS-ссылку, sing-box JSON или подписку. После импорта здесь появятся управление подключением и итоговая конфигурация.", color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { onImport(null) }) { Text("+ Добавить профиль") }
                    }
                }
            } else {
                val endpoint = remember(selected.selectedOutbound?.singBoxJson) {
                    selected.selectedOutbound?.let { OutboundPatch.read(it.singBoxJson) }
                }
                val active = busy || tunnel.status in setOf(TunnelStatus.STARTING, TunnelStatus.RUNNING, TunnelStatus.RECONNECTING)
                Panel {
                    saved.groups.firstOrNull { it.id == selected.groupId }?.let { group ->
                        Text(group.name + if (group.autoSwap) "  ⇄ авто-смена" else "", color = blue, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ВЫБРАННОЕ ПОДКЛЮЧЕНИЕ", color = blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(selected.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(selected.selectedOutbound?.type?.uppercase(),
                                if (controller.effectiveMode(selected) == RunMode.PROXY) "Локальный прокси" else "VPN · весь трафик")
                                .joinToString("  ·  "), color = muted, fontSize = 13.sp)
                            Text(endpoint?.server.orEmpty(), color = muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ConnectionPower(active = active, busy = busy, onClick = {
                            if (active) controller.disconnect() else controller.connect()
                        })
                    }
                    HomeModeSelector(saved, selected, selectionLocked, controller, onRequestElevation)
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val probe = probes[selected.id]
                        ConnectionLatency("СЕРВЕР ${probe?.serverProtocol ?: EndpointProbe.protocol(selected)}",
                            status = when {
                                probe?.latencyMs != null -> "Сервер отвечает"
                                probe?.checking == true -> "Проверяем сервер…"
                                probe != null -> "Нет ответа"
                                else -> "Не проверен"
                            },
                            value = probe?.latencyMs?.let { "$it мс" } ?: "—",
                            healthy = probe?.latencyMs != null,
                            modifier = Modifier.weight(1f))
                        val channelLatency = if (active && tunnelLatencyMs != null) tunnelLatencyMs else probe?.tunnelLatencyMs
                        ConnectionLatency("КАНАЛ HTTP", status = when {
                            channelLatency != null -> "Через профиль доступен"
                            probe?.checking == true -> "Проверяем канал…"
                            !probe?.tunnelMessage.isNullOrBlank() -> "Не работает"
                            else -> "Не проверен"
                        }, value = channelLatency?.let { "$it мс" } ?: "—",
                            healthy = channelLatency != null, modifier = Modifier.weight(1f))
                    }
                    Text("Сервер: TCP или TLS-рукопожатие до узла. Канал: HTTP через временный локальный прокси. Создание Windows VPN/TUN проверяется отдельно при подключении.",
                        color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                    probes[selected.id]?.let { probe ->
                        if (!probe.checking && probe.tunnelLatencyMs == null && probe.tunnelMessage.isNotBlank()) {
                            Text(probe.tunnelMessage, color = desktopColors.error, fontSize = 12.sp, maxLines = 3,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { controller.probe(listOf(selected.id)) }, enabled = probes[selected.id]?.checking != true) {
                            Text(if (probes[selected.id]?.checking == true) "Проверяем…" else "Проверить подключение")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { editProfile = selected }) { Text("Изменить") }
                        TextButton(onClick = { delete = selected }) { Text("Удалить") }
                    }
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
                    Text(if (showDetails) "Скрыть параметры профиля  ▴" else "Параметры профиля и JSON  ▾")
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
                        "PROFILE" -> "Из профиля"
                        else -> "DNS устройства"
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
                                if (controller.setGroup(selected.id, group.id)) expanded = expanded + group.id
                                moveMenu = false
                            }) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val preview = remember(selected, saved.rules, saved.mode, saved.defaultDnsPolicy, saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer) {
                    runCatching { controller.preview(selected.id) }.getOrNull()
                }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Итоговый sing-box JSON", fontWeight = FontWeight.SemiBold)
                            Text("Техническая конфигурация · содержит ключи доступа", color = muted, fontSize = 12.sp)
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
        }
    }
    editProfile?.let { profile -> DesktopProfileEditor(profile, saved, controller) { editProfile = null } }
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
            text = { Text("Профиль и его правила будут удалены.") },
            confirmButton = {
                TextButton(onClick = { controller.delete(profile.id); delete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun HomeModeSelector(saved: StoredState, profile: StoredProfile?, locked: Boolean,
    controller: DesktopController, onRequestElevation: () -> Unit) {
    val requestedMode = profile?.modeOverride ?: saved.mode
    val effectiveMode = controller.effectiveMode(profile).name
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Режим подключения", color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        SegmentedChoice(
            choices = listOf(RunMode.FULL_VPN.name to "VPN · весь трафик", RunMode.PROXY.name to "Локальный прокси"),
            selected = effectiveMode,
            enabled = !locked,
            onSelect = { value ->
                controller.switchMode(profile, RunMode.valueOf(value))
                if (value == RunMode.FULL_VPN.name && !WindowsElevation.isElevated) onRequestElevation()
            },
        )
        Text(if (!WindowsElevation.isElevated && requestedMode == RunMode.FULL_VPN.name)
            "Без прав администратора используется локальный прокси SOCKS/HTTP 127.0.0.1:2080. Для полного VPN выберите его снова и подтвердите запрос Windows."
        else if (effectiveMode == RunMode.PROXY.name)
            "Только приложения с прокси SOCKS/HTTP 127.0.0.1:2080. Системный трафик идёт напрямую."
        else "Направляет трафик устройства по правилам LerNET. Права администратора подтверждены.",
            color = muted, fontSize = 11.sp, lineHeight = 16.sp)
        if (locked) Text("Дождитесь завершения переключения.", color = blue, fontSize = 11.sp)
        else if (profile?.modeOverride != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Режим задан для этого профиля", color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.clearModeOverride(profile.id) }) { Text("Как для всех") }
            }
        }
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
private fun ConnectionPower(active: Boolean, busy: Boolean, onClick: () -> Unit) {
    val accent by animateColorAsState(if (active) green else blue, tween(250), label = "power-color")
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .90f else if (hovered) .955f else 1f, tween(160), label = "power-depth")
    val elevation by animateDpAsState(if (pressed) 0.dp else if (hovered) 2.dp else 9.dp, tween(160), label = "power-shadow")
    val glow by animateFloatAsState(if (pressed) .12f else if (hovered) .20f else .30f, tween(180), label = "power-glow")
    Column(Modifier.width(130.dp).hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
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
        Spacer(Modifier.height(12.dp))
        Text(if (busy) "Подождите…" else if (active) "Отключить" else "Подключить",
                color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 3.dp))
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
internal fun DragHandle(onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Canvas(Modifier.width(28.dp).height(40.dp).onGloballyPositioned { origin = it.boundsInWindow().topLeft }
        .pointerInput(Unit) {
            detectDragGestures(onDragStart = { start(origin + it) }, onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
                change.consume()
                drag(amount)
            }
        }) {
        val radius = 1.8.dp.toPx()
        val gap = 6.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        for (row in -1..1) for (column in 0..1) {
            drawCircle(muted, radius, Offset(center.x + (column - .5f) * gap, center.y + row * gap))
        }
    }
}

@Composable
private fun FolderChevron(expanded: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.6.dp.toPx()
        drawCircle(blue.copy(alpha = .55f), radius = size.minDimension * .44f, center = center, style = Stroke(stroke))
        drawLine(blue, Offset(size.width * .28f, center.y), Offset(size.width * .72f, center.y), stroke)
        if (!expanded) drawLine(blue, Offset(center.x, size.height * .28f), Offset(center.x, size.height * .72f), stroke)
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
    dragging: Boolean, dropTarget: Boolean, indent: Boolean, dragOffset: Offset,
    onBounds: (Rect) -> Unit, onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit,
    onDelete: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = if (indent) 14.dp else 0.dp).onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .graphicsLayer { translationY = if (dragging) dragOffset.y else 0f; shadowElevation = if (dragging) 20f else 0f }
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
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileGlyph()
                Spacer(Modifier.width(9.dp))
                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
                DragHandle(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd)
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.size(28.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("⋮", fontSize = 19.sp)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Проверить подключение") }, onClick = { onProbe(); menu = false })
                        DropdownMenuItem(text = { Text("Выше") }, onClick = { onMove(-1); menu = false })
                        DropdownMenuItem(text = { Text("Ниже") }, onClick = { onMove(1); menu = false })
                        DropdownMenuItem(text = { Text("Переместить в папку…") }, onClick = { menu = false; folderMenu = true })
                        DropdownMenuItem(text = { Text("Дублировать") }, onClick = { onDuplicate(); menu = false })
                    }
                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text("Без папки") }, onClick = { onSetGroup(null); folderMenu = false })
                        groups.forEach { group ->
                            DropdownMenuItem(text = { Text(group.name) }, onClick = { onSetGroup(group.id); folderMenu = false })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProbeMiniMetric("Сервер ${probe?.serverProtocol ?: EndpointProbe.protocol(profile)}",
                    when {
                        probe?.latencyMs != null -> "Отвечает"
                        probe?.checking == true -> "Проверка…"
                        probe != null -> "Нет ответа"
                        else -> "Не проверен"
                    }, probe?.latencyMs?.let { "$it мс" } ?: "—", probe?.latencyMs != null, Modifier.weight(1f))
                ProbeMiniMetric("Канал HTTP", when {
                    probe?.tunnelLatencyMs != null -> "Доступен"
                    probe?.checking == true -> "Проверка…"
                    !probe?.tunnelMessage.isNullOrBlank() -> "Не работает"
                    else -> "Не проверен"
                }, probe?.tunnelLatencyMs?.let { "$it мс" } ?: "—",
                    probe?.tunnelLatencyMs != null, Modifier.weight(1f))
                TextButton(onClick = onProbe, enabled = probe?.checking != true, modifier = Modifier.size(28.dp),
                    contentPadding = PaddingValues(0.dp)) { Text("↻", fontSize = 19.sp) }
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
                    Text("ТЕКУЩИЙ ПРОФИЛЬ", color = blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(activeProfile?.name ?: "Профиль не выбран", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val groupName = saved.groups.firstOrNull { it.id == activeProfile?.groupId }?.name
                    Text(listOfNotNull(groupName, activeProfile?.selectedOutbound?.type?.uppercase()).joinToString(" · ").ifBlank { "Выберите профиль на главном экране" }, color = muted, fontSize = 12.sp)
                }
                Text(tunnel.message.ifBlank { tunnel.status.name }, color = statusColor(tunnel.status), maxLines = 1)
            }
        }
        Spacer(Modifier.height(11.dp))
        TraceView(trace, traceDetailsRequest, onRefresh = controller::traceSelected)
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelMetric("Состояние", tunnel.message.ifBlank { tunnel.status.name }, statusColor(tunnel.status), Modifier.weight(1f))
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
            FilterChip(display == "log", onClick = { display = "log" }, label = { Text("Журнал ядра") })
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
                    item { Panel { Text(if (display == "history") "За этот запуск соединений пока нет" else if (tunnel.status == TunnelStatus.RUNNING) "Активных соединений сейчас нет" else "Подключите профиль, чтобы видеть соединения", color = muted) } }
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
                Text("Маршрут до сервера", fontWeight = FontWeight.SemiBold)
                Text(server.ifBlank { "Адрес появится после выбора профиля" }, color = muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onTrace, enabled = server.isNotBlank() && !trace.running) {
                Text(if (trace.running) "Ищем узлы…" else "Проверить хопы")
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
                TextButton(onClick = onRefresh) { Text("Обновить") }
                TextButton(onClick = { expanded = !expanded }, enabled = trace.hops.isNotEmpty()) { Text(if (expanded) "Свернуть" else "Подробности") }
            }
            Text(trace.message.ifBlank { "Маршрут появится после подключения или ручной проверки" }, color = muted, fontSize = 12.sp)
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
private fun Settings(saved: StoredState, controller: DesktopController) {
    var path by remember(saved.corePath) { mutableStateOf(saved.corePath) }
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
    val defaultsChanged = mtu != saved.tunMtu.toString() || "$xmuxMin-$xmuxMax" != saved.xmuxConcurrency ||
        directDns.trim() != saved.directDnsServer || dnsPolicy != saved.defaultDnsPolicy || logLevel != saved.logLevel
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            SettingHeading("ЗАПУСК", "Режим по умолчанию",
                "VPN направляет трафик устройства через туннель. Прокси работает только для приложений, настроенных на 127.0.0.1:2080.")
            SegmentedChoice(
                listOf(RunMode.FULL_VPN.name to "VPN · весь трафик", RunMode.PROXY.name to "Локальный прокси"),
                saved.mode,
            ) { controller.switchMode(null, RunMode.valueOf(it)) }
            Text(
                if (saved.mode == RunMode.FULL_VPN.name)
                    "При запуске Windows запросит права администратора. Если доступ не предоставлен, LerNET откроется в режиме прокси. Профиль может иметь собственный режим."
                else "LerNET откроется без запроса прав администратора. Режим можно изменить на главном экране.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
        Panel {
            Text("Параметры подключения", style = MaterialTheme.typography.titleLarge)
            Text("Общие значения для профилей. Если профиль содержит собственную настройку, она имеет приоритет. Изменения вступят в силу при следующем подключении.",
                color = muted, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = desktopColors.outlineVariant)
            SettingHeading("DNS", "Кто определяет адреса сайтов",
                "DNS переводит имя сайта в IP-адрес. Выберите источник настройки рядом с адресом сервера.")
            Box(Modifier.fillMaxWidth()) {
                SegmentedChoice(listOf("UNDERLAY" to "LerNET · вручную", "PROFILE" to "Из профиля"), dnsPolicy) { dnsPolicy = it }
            }
            OutlinedTextField(directDns, { directDns = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (dnsPolicy == "PROFILE") "Резервный DNS · IPv4" else "DNS-сервер · IPv4") },
                placeholder = { Text("1.1.1.1") }, isError = !dnsValid, singleLine = true,
                supportingText = { Text(if (!dnsValid) "Введите один IPv4-адрес, например 1.1.1.1." else "Один IPv4-адрес. Список через запятую здесь не поддерживается.") })
            Text(if (dnsPolicy == "PROFILE")
                "Если в профиле есть DNS, используются его серверы и правила. Импортированный JSON может содержать несколько серверов; их выбор задаёт конфигурация. Если DNS в профиле отсутствует, используется адрес выше. Он также нужен для прямых DNS-зависимостей."
            else "Для DNS используется адрес, заданный здесь, через прямое подключение. Несколько DNS можно задать в JSON профиля и выбрать «Из профиля». Это отдельная настройка LerNET, а не автоматический выбор DNS Windows.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = desktopColors.outlineVariant)
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
            Text("Первое число — нижняя граница предела, второе — верхняя. Это не число загрузок и отдач. При достижении предела создаётся ещё одно соединение. Настройка применяется только к XHTTP, если профиль не задаёт собственный XMUX.",
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
        Panel {
            SettingHeading("HTTPS", "Проверка работоспособности",
                "Через этот адрес проверяется интернет через выбранный профиль: перед подключением и во время работы VPN.")
            OutlinedTextField(healthUrl, { healthUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Адрес проверки") },
                singleLine = true, isError = !healthUrl.startsWith("https://"))
            Text("Во время подключения приложение проверяет путь через Windows каждые 12 секунд и после смены сети. Две неудачи подряд запускают переподключение или смену сервера внутри папки, если она включена. Используйте небольшой HTTPS-ресурс, доступный через VPN.",
                color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            Button(onClick = { controller.setHealthUrl(healthUrl) }, enabled = healthUrl.startsWith("https://") && healthUrl != saved.healthUrl) { Text("Сохранить адрес") }
        }
        Panel {
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
            SettingHeading("ЯДРО", "sing-box-lx " + WindowsBoxProcess.PINNED_CORE_VERSION,
                "Встроенное ядро поддерживает Android-профили и XHTTP. Пустое поле использует комплектную версию.")
            OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Свой путь к sing-box.exe") }, singleLine = true)
            Button(onClick = { controller.setCorePath(path) }, enabled = path != saved.corePath) { Text("Сохранить путь") }
        }
    }
}

@Composable
private fun SettingHeading(tag: String, title: String, description: String) {
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
        title = { Text(if (groupName == null) "Импорт профиля" else "Импорт в папку «$groupName»") },
        text = {
            Column {
                Text("VLESS-ссылка, sing-box JSON или URL подписки", color = muted)
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
                                check(Files.size(file) <= 5_000_000) { "Файл больше 5 МБ" }
                                Files.readString(file)
                            }.onSuccess { value = it; error = "" }
                                .onFailure { error = it.message ?: "Не удалось прочитать файл" }
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
