package app.lernet.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.lernet.engine.compile.DnsDraft
import app.lernet.engine.compile.DnsServerDraft
import app.lernet.engine.compile.OutboundPatch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class EditorPage(val title: String) { LOCAL("На устройстве"), REMOTE("Удалённый сервер"), JSON("Итоговый JSON") }
private val editorMuted = Color(0xFFA1AEC4)
private val editorWarning = Color(0xFFF5C16C)

@Composable
fun DesktopProfileEditor(profile: StoredProfile, saved: StoredState, controller: DesktopController, onClose: () -> Unit) {
    val outbound = profile.selectedOutbound ?: return
    val original = remember(profile.id) { OutboundPatch.read(outbound.singBoxJson) }
    val originalDns = remember(profile.id) { DnsDraft.read(profile.dnsJson).firstOrNull() }
    var page by remember(profile.id) { mutableStateOf(EditorPage.LOCAL) }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var server by remember(profile.id) { mutableStateOf(original.server) }
    var port by remember(profile.id) { mutableStateOf(original.port) }
    var sni by remember(profile.id) { mutableStateOf(original.sni) }
    var path by remember(profile.id) { mutableStateOf(original.path) }
    var transportMode by remember(profile.id) { mutableStateOf(original.mode) }
    var runMode by remember(profile.id) { mutableStateOf(profile.modeOverride ?: "SYSTEM") }
    var dnsPolicy by remember(profile.id) { mutableStateOf(profile.dnsPolicy) }
    var dnsType by remember(profile.id) { mutableStateOf(originalDns?.type?.ifBlank { "udp" } ?: "udp") }
    var dnsServer by remember(profile.id) { mutableStateOf(originalDns?.server.orEmpty()) }
    var dnsTypeOpen by remember { mutableStateOf(false) }
    var xhttpModeOpen by remember { mutableStateOf(false) }

    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val effectiveDnsPolicy = if (dnsPolicy == "SYSTEM") saved.defaultDnsPolicy else dnsPolicy
    val dnsValid = effectiveDnsPolicy != "PROFILE" || dnsServer.isNotBlank()
    val valid = name.isNotBlank() && server.isNotBlank() && parsedPort != null && dnsValid
    val candidate = remember(name, server, port, sni, path, transportMode, runMode, dnsPolicy, dnsType, dnsServer, saved.defaultDnsPolicy) {
        val patched = if (parsedPort != null) OutboundPatch.write(outbound.singBoxJson, server, parsedPort, sni, path, transportMode) else outbound.singBoxJson
        val dns = if (effectiveDnsPolicy == "PROFILE") DnsDraft.write(
            profile.dnsJson,
            listOf(DnsServerDraft(0, dnsType, originalDns?.tag?.ifBlank { "dns-profile" } ?: "dns-profile", dnsServer, originalDns?.detour.orEmpty())),
        ) else profile.dnsJson
        profile.copy(
            name = name.trim(),
            outbounds = profile.outbounds.map { if (it.id == profile.selectedOutboundId) it.copy(singBoxJson = patched) else it },
            dnsJson = dns,
            dnsPolicy = dnsPolicy,
            modeOverride = runMode.takeUnless { it == "SYSTEM" },
        )
    }
    val preview = remember(candidate, saved.rules, saved.mode, saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer) {
        if (valid) runCatching { controller.preview(profile.id, candidate) }.getOrNull() else null
    }

    DialogWindow(onCloseRequest = onClose, title = "Профиль · ${profile.name}", state = rememberDialogState(size = DpSize(860.dp, 760.dp))) {
        LaunchedEffect(window) { WindowsTitleBar.dark(window) }
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = Color(0xFF101724), contentColor = desktopColors.onSurface) {
                Column(Modifier.fillMaxSize().padding(22.dp)) {
                    Text("Редактор профиля", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Локальные параметры устройства и параметры удалённого сервера разделены.", color = editorMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        EditorPage.entries.forEach { item -> FilterChip(page == item, onClick = { page = item }, label = { Text(item.title) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        when (page) {
                            EditorPage.LOCAL -> {
                                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Название профиля") }, singleLine = true)
                                Text("Режим подключения", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    listOf("SYSTEM" to "Определено глобально", "FULL_VPN" to "Полный VPN", "PROXY" to "Локальный прокси").forEach { (value, label) ->
                                        FilterChip(runMode == value, onClick = { runMode = value }, label = { Text(label) })
                                    }
                                }
                                if (runMode == "SYSTEM") Text("Сейчас глобально: ${if (saved.mode == "PROXY") "локальный прокси" else "полный VPN"}", color = editorWarning, fontSize = 12.sp)
                                HorizontalDivider()
                                Text("DNS", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    FilterChip(dnsPolicy == "SYSTEM", onClick = { dnsPolicy = "SYSTEM" }, label = { Text("Глобально") })
                                    FilterChip(dnsPolicy == "UNDERLAY", onClick = { dnsPolicy = "UNDERLAY" }, label = { Text("DNS устройства") })
                                    FilterChip(dnsPolicy == "PROFILE", onClick = { dnsPolicy = "PROFILE" }, label = { Text("DNS профиля") })
                                }
                                if (dnsPolicy == "SYSTEM") Text("Глобально: ${if (saved.defaultDnsPolicy == "PROFILE") "DNS профиля" else "DNS устройства"}", color = editorWarning, fontSize = 12.sp)
                                if (effectiveDnsPolicy == "PROFILE") {
                                    Box {
                                        OutlinedButton(onClick = { dnsTypeOpen = true }) { Text("Тип: $dnsType") }
                                        DropdownMenu(dnsTypeOpen, onDismissRequest = { dnsTypeOpen = false }) {
                                            (listOf("udp", "tcp", "tls", "https", "quic", "h3") + dnsType).distinct().forEach { value ->
                                                DropdownMenuItem(text = { Text(value) }, onClick = { dnsType = value; dnsTypeOpen = false })
                                            }
                                        }
                                    }
                                    OutlinedTextField(dnsServer, { dnsServer = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Адрес DNS-сервера") },
                                        isError = !dnsValid, supportingText = { Text("Должен соответствовать выбранному типу DNS") })
                                } else Text("Используется DNS устройства: ${saved.directDnsServer}. Значение задаётся в глобальных настройках.", color = editorWarning, fontSize = 12.sp)
                                HorizontalDivider()
                                Text("Глобальные значения: MTU ${saved.tunMtu} · XMUX ${saved.xmuxConcurrency} · журнал ${saved.logLevel}", color = editorMuted, fontSize = 12.sp)
                            }
                            EditorPage.REMOTE -> {
                                Text("Эти поля должны совпадать с настройками принимающего сервера.", color = editorWarning, fontSize = 12.sp)
                                OutlinedTextField(server, { server = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Адрес сервера") }, isError = server.isBlank(), singleLine = true)
                                OutlinedTextField(port, { port = it }, modifier = Modifier.width(210.dp), label = { Text("Порт") }, isError = parsedPort == null, singleLine = true)
                                OutlinedTextField(sni, { sni = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SNI / server_name") }, singleLine = true)
                                Text("Транспорт: ${original.transportType.ifBlank { "не указан" }}", color = editorMuted)
                                if (original.hasTransport) {
                                    OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Путь транспорта") }, singleLine = true)
                                    if (original.transportType in listOf("xhttp", "splithttp")) {
                                        Box {
                                            OutlinedButton(onClick = { xhttpModeOpen = true }) { Text("Режим XHTTP: ${transportMode.ifBlank { "определено ядром" }}") }
                                            DropdownMenu(xhttpModeOpen, onDismissRequest = { xhttpModeOpen = false }) {
                                                (listOf("", "auto", "packet-up", "stream-up", "stream-one") + transportMode).distinct().forEach { value ->
                                                    DropdownMenuItem(text = { Text(value.ifBlank { "Определено ядром" }) }, onClick = { transportMode = value; xhttpModeOpen = false })
                                                }
                                            }
                                        }
                                        Text("Режим меняет способ обмена данными с сервером. Выбирайте только режим, который он поддерживает.", color = editorMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                            EditorPage.JSON -> {
                                Row {
                                    Text("Итоговый sing-box JSON", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        preview?.json?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) }
                                    }, enabled = preview?.isValid == true) { Text("Копировать") }
                                }
                                if (preview?.isValid == true) {
                                    SelectionContainer { Text(preview.json, fontFamily = FontFamily.Monospace, color = editorMuted, fontSize = 11.sp) }
                                } else Text(preview?.errors?.joinToString("; ") ?: "Заполните адрес и порт сервера", color = desktopColors.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (valid && preview?.isValid == false) Text(preview.errors.take(2).joinToString("; "), color = desktopColors.error, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Отмена") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { controller.updateProfile(candidate); onClose() }, enabled = valid && preview?.isValid == true) { Text("Сохранить") }
                    }
                }
            }
        }
    }
}
