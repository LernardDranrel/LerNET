package app.lernet.desktop

import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.VerifiedInterfaceBinding
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class WindowsAdapterFact(
    val binding: VerifiedInterfaceBinding,
    val description: String,
    val status: String,
    val eligible: Boolean,
    val reason: String?,
)

/** Read-only discovery. A displayed snapshot is not permission to use a changed adapter later. */
internal object WindowsAdapterInventory {
    fun read(excludedIndex: Int? = null): List<WindowsAdapterFact> = parse(readWindowsAdapterJson(SCRIPT), excludedIndex)

    internal fun parse(raw: String, excludedIndex: Int?): List<WindowsAdapterFact> {
        val values = Json.parseToJsonElement(raw.trim().removePrefix("\uFEFF")) as? JsonArray
            ?: error("Windows вернула неверный список сетевых интерфейсов")
        return values.mapNotNull { entry ->
            val value = entry as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val index = (value["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return@mapNotNull null
            val binding = ExternalExitProfiles.normalizeBinding(
                VerifiedInterfaceBinding(text("guid").orEmpty(), text("name").orEmpty(), index)
            )
                ?: return@mapNotNull null
            val status = text("status").orEmpty()
            val reason = when {
                index == excludedIndex -> "Это общий TUN LerNET: направлять выход обратно в него нельзя"
                !status.equals("Up", true) -> "Интерфейс сейчас отключён; подключите корпоративный VPN и обновите список"
                else -> null
            }
            WindowsAdapterFact(
                binding, text("description").orEmpty().take(512),
                if (status.equals("Up", true)) "Подключён" else "Отключён", reason == null, reason
            )
        }.distinctBy { it.binding.guid }.sortedWith(compareByDescending<WindowsAdapterFact> { it.eligible }.thenBy { it.binding.name })
    }

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${ '$' }false)
        ${'$'}rows = @(Get-NetAdapter -IncludeHidden | ForEach-Object {
            [pscustomobject]@{
                guid = [string]${'$'}_.InterfaceGuid; name = ${'$'}_.Name; index = [int]${'$'}_.ifIndex
                status = [string]${'$'}_.Status; description = ${'$'}_.InterfaceDescription
            }
        })
        ConvertTo-Json -InputObject ${'$'}rows -Compress
    """.trimIndent()
}

/** Only constant read-only discovery scripts from this package are passed to this helper. */
internal fun readWindowsAdapterJson(script: String): String {
    val executable = Path.of(requireNotNull(System.getenv("SystemRoot")), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
    val process = ProcessBuilder(
        executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", encoded
    )
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = CompletableFuture<String>()
    thread(name = "lernet-adapter-inventory", isDaemon = true) {
        try {
            val bytes = process.inputStream.use { it.readNBytes(65_537) }
            check(bytes.size <= 65_536) { "Список сетевых интерфейсов превышает допустимый размер" }
            output.complete(bytes.toString(Charsets.UTF_8))
        } catch (failure: Exception) {
            output.completeExceptionally(failure)
        }
    }
    try {
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Windows не предоставила список сетевых интерфейсов за 30 секунд" }
        check(process.exitValue() == 0) { "Windows не предоставила сведения о сетевых интерфейсах" }
        return output.get(1, TimeUnit.SECONDS)
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
