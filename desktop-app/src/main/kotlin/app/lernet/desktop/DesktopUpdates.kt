package app.lernet.desktop

import app.lernet.config.update.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sun.jna.platform.win32.Shell32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

internal object DesktopUpdates {
    private val preferences by lazy { Preferences.userRoot().node("app/lernet/updates") }
    private val version by lazy { DesktopUpdates::class.java.getResourceAsStream("/lernet-version.txt")!!.bufferedReader().use { it.readText().trim() } }
    val monitor by lazy { UpdateMonitor(version,
        { preferences.getBoolean("automatic", true) }, { preferences.putBoolean("automatic", it) },
        { preferences.getLong("lastCheck", 0) }, { preferences.putLong("lastCheck", it) }) }
    val installed: Boolean by lazy {
        runCatching { java.nio.file.Path.of(ProcessHandle.current().info().command().orElse(""))
            .toAbsolutePath().parent?.resolve("unins000.exe")?.let(Files::isRegularFile) == true }.getOrDefault(false)
    }

    fun openPage(url: String) { Desktop.getDesktop().browse(URI(url)) }

    /** Download into a unique directory, verify exact size and GitHub's SHA-256 before UAC. */
    fun download(asset: ReleaseAsset, progress: (Int) -> Unit): java.nio.file.Path {
        val digest = requireNotNull(asset.sha256)
        val directory = Files.createTempDirectory("LerNET-update-")
        val target = directory.resolve(asset.name)
        try {
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
            client.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
                check(response.isSuccessful) { "Не удалось скачать установщик: HTTP ${response.code}" }
                response.body!!.byteStream().use { input -> Files.newOutputStream(target).use { output ->
                    copyVerified(input, output, asset.size, digest, progress)
                } }
            }
            return target
        } catch (error: Exception) { Files.deleteIfExists(target); Files.deleteIfExists(directory); throw error }
    }

    internal fun copyVerified(input: java.io.InputStream, output: java.io.OutputStream,
        size: Long, digest: String, progress: (Int) -> Unit = {}) {
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        var last = -1
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= size) { "Размер установщика не совпадает с релизом" }
            hash.update(buffer, 0, count); output.write(buffer, 0, count)
            val percent = (total * 100 / size).toInt()
            if (percent != last) { progress(percent); last = percent }
        }
        check(total == size && hash.digest().joinToString("") { "%02x".format(it) } == digest) {
            "Проверка установщика не пройдена. Файл не будет запущен."
        }
    }

    fun runInstaller(path: java.nio.file.Path) {
        check(Shell32.INSTANCE.ShellExecute(null, "runas", path.toString(), null, path.parent.toString(), 1).toLong() > 32) {
            "Запуск установщика отменён или Windows не дала разрешение. Можно повторить обновление."
        }
    }
}

@Composable
internal fun DesktopUpdatePrompt() {
    val monitor = remember { DesktopUpdates.monitor }
    val state by monitor.state.collectAsState()
    var dismissed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { monitor.check() }
    state.release?.takeIf { it.version != dismissed }?.let { release ->
        AlertDialog(onDismissRequest = { dismissed = release.version },
            title = { Text("Доступен LerNET v${release.version}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (DesktopUpdates.installed)
                    "Установщик обновит приложение и сохранит ваши профили. При обновлении VPN временно отключится."
                    else "Вы используете портативную версию. Скачайте новый ZIP на странице релиза, выйдите из LerNET через меню в трее и замените файлы. Профили сохранятся.")
                DesktopUpdateActions(release)
            } },
            confirmButton = { TextButton(onClick = { dismissed = release.version }) { Text("Позже") } })
    }
}

@Composable
internal fun DesktopUpdateCard() {
    val monitor = remember { DesktopUpdates.monitor }
    val state by monitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Обновления LerNET", style = MaterialTheme.typography.titleLarge)
        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.automatic, onCheckedChange = monitor::automatic)
            Text("Проверять при запуске, не чаще раза в сутки")
        }
        Text("Запрос идёт на GitHub. Профили и данные диагностики не отправляются.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { scope.launch { monitor.check(manual = true) } }, enabled = !state.checking) { Text("Проверить обновления") }
        state.release?.let { DesktopUpdateActions(it) }
    }
}

@Composable
private fun DesktopUpdateActions(release: ClientRelease) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val asset = release.windowsInstaller()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (asset != null && DesktopUpdates.installed) Button(enabled = !downloading, onClick = {
            downloading = true; message = ""
            scope.launch {
                try {
                    val installer = withContext(Dispatchers.IO) { DesktopUpdates.download(asset) { value -> scope.launch { percent = value } } }
                    DesktopUpdates.runInstaller(installer)
                    message = "Установщик запущен. Продолжите обновление в его окне."
                } catch (error: Exception) { message = error.message ?: "Не удалось обновить LerNET" }
                finally { downloading = false }
            }
        }) { Text(if (downloading) "Скачиваем… $percent%" else "Скачать и обновить") }
        if (!DesktopUpdates.installed) Text("Для портативной версии скачайте новый ZIP и замените файлы после выхода из LerNET.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { runCatching { DesktopUpdates.openPage(release.pageUrl) }.onFailure { message = "Не удалось открыть браузер" } }) { Text("Что нового — открыть релиз") }
        if (message.isNotBlank()) Text(message)
    }
}
