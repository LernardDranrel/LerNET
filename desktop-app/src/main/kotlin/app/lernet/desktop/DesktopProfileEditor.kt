package app.lernet.desktop

import app.lernet.engine.text.EditorWords
import app.lernet.engine.text.SettingsWords
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import app.lernet.engine.compile.TlsHandshake
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal enum class EditorPage(val title: String) { LOCAL(EditorWords.LOCAL), REMOTE(EditorWords.REMOTE), JSON(EditorWords.ENGINE) }
private val editorMuted = Color(0xFFA1AEC4)
private val editorWarning = Color(0xFFF5C16C)

@Composable
fun DesktopProfileEditor(
    profile: StoredProfile,
    saved: StoredState,
    controller: DesktopController,
    onClose: () -> Unit,
) = DesktopProfileEditor(profile, saved, controller, onClose, embedded = false)

/** [embedded] draws the same editor without its own window, for the screenshot tests. */
@Composable
internal fun DesktopProfileEditor(
    profile: StoredProfile,
    saved: StoredState,
    controller: DesktopController,
    onClose: () -> Unit,
    embedded: Boolean,
    startPage: EditorPage = EditorPage.LOCAL,
) {
    val outbound = profile.selectedOutbound ?: return
    val original = remember(profile.id) { OutboundPatch.read(outbound.singBoxJson) }
    val originalDns = remember(profile.id) { DnsDraft.read(profile.dnsJson).firstOrNull() }
    var page by remember(profile.id) { mutableStateOf(startPage) }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var server by remember(profile.id) { mutableStateOf(original.server) }
    var port by remember(profile.id) { mutableStateOf(original.port) }
    var sni by remember(profile.id) { mutableStateOf(original.sni) }
    var path by remember(profile.id) { mutableStateOf(original.path) }
    var transportMode by remember(profile.id) { mutableStateOf(original.mode) }
    var fingerprint by remember(profile.id) { mutableStateOf(original.fingerprint) }
    var runMode by remember(profile.id) { mutableStateOf(profile.modeOverride ?: "SYSTEM") }
    // Each config has its own DNS choice (as on Android); a legacy «SYSTEM» shows as the global value.
    var dnsPolicy by remember(profile.id) { mutableStateOf(profile.dnsPolicy.takeUnless { it == "SYSTEM" } ?: saved.defaultDnsPolicy) }
    var dnsType by remember(profile.id) { mutableStateOf(originalDns?.type?.ifBlank { "udp" } ?: "udp") }
    var dnsServer by remember(profile.id) { mutableStateOf(originalDns?.server.orEmpty()) }
    var dnsTypeOpen by remember { mutableStateOf(false) }
    var xhttpModeOpen by remember { mutableStateOf(false) }

    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val effectiveDnsPolicy = if (dnsPolicy == "SYSTEM") saved.defaultDnsPolicy else dnsPolicy
    val dnsValid = effectiveDnsPolicy != "PROFILE" || dnsServer.isNotBlank()
    val valid = name.isNotBlank() && server.isNotBlank() && parsedPort != null && dnsValid
    val candidate = remember(
        name, server, port, sni, path, transportMode, fingerprint, runMode, dnsPolicy, dnsType, dnsServer, saved.defaultDnsPolicy,
    ) {
        // Only a fingerprint changed here is written; an untouched profile keeps its TLS block as is.
        val changedFingerprint = fingerprint.takeIf { it != original.fingerprint }
        val patched = if (parsedPort != null) {
            OutboundPatch.write(outbound.singBoxJson, server, parsedPort, sni, path, transportMode, changedFingerprint)
        } else {
            outbound.singBoxJson
        }
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
    val preview = remember(candidate, saved.rules, saved.mode, saved.tunMtu, saved.xmuxChannels, saved.directDnsServer) {
        if (valid) runCatching { controller.preview(profile.id, candidate) }.getOrNull() else null
    }

    val content: @Composable () -> Unit = {
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = Color(0xFF101724), contentColor = desktopColors.onSurface) {
                Column(Modifier.fillMaxSize().padding(22.dp)) {
                    // As on the phone: the config's own name is the title.
                    Text(profile.name.ifBlank { EditorWords.TITLE }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        EditorPage.entries.forEach { item -> FilterChip(page == item, onClick = { page = item }, label = { Text(item.title) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        when (page) {
                            EditorPage.LOCAL -> {
                                Text(EditorWords.LOCAL_HELP, color = editorMuted, fontSize = 12.sp)
                                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Название конфига") }, singleLine = true)
                                Text(SettingsWords.MODE, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    listOf("SYSTEM" to "Как для всех", "FULL_VPN" to SettingsWords.MODE_VPN, "PROXY" to SettingsWords.MODE_PROXY).forEach { (value, label) ->
                                        FilterChip(runMode == value, onClick = { runMode = value }, label = { Text(label) })
                                    }
                                }
                                if (runMode == "SYSTEM") Text("Сейчас для всех: ${if (saved.mode == "PROXY") "прокси" else "полный VPN"}", color = editorWarning, fontSize = 12.sp)
                                HorizontalDivider()
                                Text(EditorWords.DNS, fontWeight = FontWeight.SemiBold)
                                Text(EditorWords.DNS_WHY, color = editorMuted, fontSize = 12.sp)
                                // The phone's three choices, word for word.
                                listOf("TUNNEL" to SettingsWords.DNS_TUNNEL, "UNDERLAY" to SettingsWords.DNS_DIRECT,
                                    "PROFILE" to SettingsWords.DNS_PROFILE).forEach { (value, label) ->
                                    Row(Modifier.clickable { dnsPolicy = value }, verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(dnsPolicy == value, onClick = { dnsPolicy = value })
                                        Text(label, fontSize = 14.sp)
                                    }
                                }
                                preview?.userChanges?.takeIf { it.isNotEmpty() }?.let { changes ->
                                    Text(app.lernet.engine.compile.ConfigNotes.HEADER, color = editorWarning, fontSize = 12.sp)
                                    changes.forEach { Text("• $it", color = editorMuted, fontSize = 12.sp) }
                                }
                                if (effectiveDnsPolicy == "PROFILE") {
                                    Box {
                                        OutlinedButton(onClick = { dnsTypeOpen = true }) { Text("${EditorWords.DNS_TYPE}: $dnsType") }
                                        DropdownMenu(dnsTypeOpen, onDismissRequest = { dnsTypeOpen = false }) {
                                            (listOf("udp", "tcp", "tls", "https", "quic", "h3") + dnsType).distinct().forEach { value ->
                                                DropdownMenuItem(text = { Text(value) }, onClick = { dnsType = value; dnsTypeOpen = false })
                                            }
                                        }
                                    }
                                    Text(EditorWords.DNS_TYPE_HELP, color = editorMuted, fontSize = 12.sp)
                                    OutlinedTextField(dnsServer, { dnsServer = it }, modifier = Modifier.fillMaxWidth(), label = { Text(EditorWords.DNS_ADDRESS) },
                                        isError = !dnsValid, supportingText = { if (!dnsValid) Text(EditorWords.DNS_ERROR) })
                                } else if (effectiveDnsPolicy == "UNDERLAY") {
                                    // Only the direct choice uses this server; «через VPN» never did (the old line said it always).
                                    Text("${SettingsWords.DIRECT_DNS}: ${saved.directDnsServer} — «${SettingsWords.TITLE}» → «${SettingsWords.EXPERTS}».",
                                        color = editorMuted, fontSize = 12.sp)
                                }
                                HorizontalDivider()
                                Text(EditorWords.ADVANCED, fontWeight = FontWeight.SemiBold)
                                Text(EditorWords.ADVANCED_HELP, color = editorMuted, fontSize = 12.sp)
                                Text("MTU ${saved.tunMtu} ${SettingsWords.BYTES} · ${SettingsWords.XHTTP_TITLE}: " +
                                    (if (saved.xmuxChannels == app.lernet.engine.compile.XhttpMode.SINGLE_CHANNEL) SettingsWords.XHTTP_SINGLE.lowercase()
                                    else saved.xmuxChannels.toString()) + " · ${SettingsWords.LOG_LEVEL.lowercase()}: " + when (saved.logLevel) {
                                        "info" -> SettingsWords.LOG_INFO
                                        "debug" -> SettingsWords.LOG_DEBUG
                                        else -> SettingsWords.LOG_WARN
                                    }.lowercase(),
                                    color = editorMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            EditorPage.REMOTE -> {
                                Text(EditorWords.REMOTE_HELP, color = editorMuted, fontSize = 12.sp) // muted, as on the phone (it is not a warning)
                                Text(EditorWords.NODE, fontWeight = FontWeight.SemiBold)
                                Text(EditorWords.NODE_WHY, color = editorMuted, fontSize = 12.sp)
                                OutlinedTextField(server, { server = it }, modifier = Modifier.fillMaxWidth(), label = { Text(EditorWords.SERVER) }, isError = server.isBlank(), singleLine = true,
                                    supportingText = { if (server.isBlank()) Text(EditorWords.SERVER_ERROR) })
                                OutlinedTextField(port, { port = it }, modifier = Modifier.width(210.dp), label = { Text(EditorWords.PORT) }, isError = parsedPort == null, singleLine = true,
                                    supportingText = { if (parsedPort == null) Text(EditorWords.PORT_ERROR) })
                                Text(EditorWords.DISGUISE, fontWeight = FontWeight.SemiBold)
                                Text(EditorWords.DISGUISE_WHY, color = editorMuted, fontSize = 12.sp)
                                OutlinedTextField(sni, { sni = it }, modifier = Modifier.fillMaxWidth(), label = { Text(EditorWords.SNI) }, singleLine = true)
                                if (original.hasTls) FingerprintChoice(fingerprint) { fingerprint = it }
                                if (original.hasTransport) {
                                    OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), label = { Text(EditorWords.PATH) }, singleLine = true)
                                    if (original.transportType in listOf("xhttp", "splithttp")) {
                                        Box {
                                            OutlinedButton(onClick = { xhttpModeOpen = true }) { Text("${EditorWords.MODE}: ${transportMode.ifBlank { EditorWords.SYSTEM_DEFINED }}") }
                                            DropdownMenu(xhttpModeOpen, onDismissRequest = { xhttpModeOpen = false }) {
                                                (listOf("", "auto", "packet-up", "stream-up", "stream-one") + transportMode).distinct().forEach { value ->
                                                    DropdownMenuItem(text = { Text(value.ifBlank { EditorWords.SYSTEM_DEFINED }) }, onClick = { transportMode = value; xhttpModeOpen = false })
                                                }
                                            }
                                        }
                                        Text(EditorWords.MODE_HELP, color = editorMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                            EditorPage.JSON -> {
                                Row {
                                    Text(EditorWords.ENGINE, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        preview?.json?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) }
                                    }, enabled = preview?.isValid == true) { Text(EditorWords.COPY_JSON) }
                                }
                                Text(EditorWords.JSON_SECRET, color = editorMuted, fontSize = 12.sp)
                                if (preview?.isValid == true) {
                                    SelectionContainer { Text(preview.json, fontFamily = FontFamily.Monospace, color = editorMuted, fontSize = 12.sp) }
                                } else Text(preview?.errors?.joinToString("; ") ?: "Заполните адрес и порт сервера", color = desktopColors.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (valid && preview?.isValid == false) Text(preview.errors.take(2).joinToString("; "), color = desktopColors.error, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Отмена") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { controller.updateProfile(candidate); onClose() }, enabled = valid && preview?.isValid == true) { Text(EditorWords.SAVE) }
                    }
                }
            }
        }
    }
    if (embedded) {
        content()
    } else {
        DialogWindow(onCloseRequest = onClose, title = "Конфиг · ${profile.name}", state = rememberDialogState(size = DpSize(860.dp, 760.dp))) {
            LaunchedEffect(window) { WindowsTitleBar.dark(window) }
            content()
        }
    }
}

/** uTLS fingerprint as written in the profile: «chrome» from an old import stays until changed here. */
@Composable
private fun FingerprintChoice(value: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val defaultLabel = EditorWords.fingerprintDefault(TlsHandshake.DEFAULT_FINGERPRINT)
    Box {
        OutlinedButton(onClick = { open = true }) { Text("${EditorWords.FINGERPRINT}: ${value.ifBlank { defaultLabel }}") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            (TlsHandshake.CHOICES + value).distinct().forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.ifBlank { defaultLabel }) },
                    onClick = {
                        onSelect(choice)
                        open = false
                    },
                )
            }
        }
    }
    Text(
        EditorWords.FINGERPRINT_HELP,
        color = editorMuted,
        fontSize = 12.sp,
    )
}
