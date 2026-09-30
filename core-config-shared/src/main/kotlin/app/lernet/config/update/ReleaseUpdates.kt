package app.lernet.config.update

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)
data class ClientRelease(val version: String, val pageUrl: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun windowsInstaller() = assets.firstOrNull { it.name == "LerNET-$version-install.exe" && it.sha256 != null }
    fun androidApk() = assets.firstOrNull { it.name == "LerNET-$version.apk" }
}

/** Shared release contract for both clients; no credentials or device report are sent. */
object ReleaseUpdates {
    const val RELEASES = "https://github.com/LernardDranrel/LerNET/releases"
    const val API = "https://api.github.com/repos/LernardDranrel/LerNET/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    fun versionParts(version: String): List<Int>? = version.removePrefix("v")
        .takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        ?.split('.')?.map { it.toIntOrNull() ?: return null }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = versionParts(candidate) ?: return false
        val b = versionParts(current) ?: return false
        for (index in a.indices) if (a[index] != b[index]) return a[index] > b[index]
        return false
    }

    fun parse(payload: String): ClientRelease {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull == false &&
            root["prerelease"]?.jsonPrimitive?.booleanOrNull == false) { "Релиз ещё не опубликован или является предварительным" }
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
        require(tag.startsWith('v') && versionParts(tag) != null) { "Неизвестный формат версии релиза" }
        val version = tag.removePrefix("v")
        val page = "$RELEASES/tag/$tag"
        require(root["html_url"]?.jsonPrimitive?.content == page) { "Неожиданный адрес релиза" }
        val prefix = "$RELEASES/download/$tag/"
        val assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = item["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = item["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (url != prefix + name || '/' in name || '\\' in name || size !in 1..600_000_000 ||
                item["state"]?.jsonPrimitive?.content != "uploaded") return@mapNotNull null
            val digest = item["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")
                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase()
            ReleaseAsset(name, url, size, digest)
        }
        return ClientRelease(version, page, root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(6000), assets)
    }

    fun latest(): ClientRelease {
        val request = Request.Builder().url(API).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10").header("User-Agent", "LerNET-update-check").build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                403, 429 -> "GitHub ограничил частоту проверок. Попробуйте позже."
                else -> "GitHub ответил HTTP ${response.code}. Попробуйте позже."
            } }
            val body = response.body ?: error("GitHub не передал данные релиза")
            body.byteStream().use { parse(readBounded(it, 1_000_000).toString(Charsets.UTF_8)) }
        }
    }

    internal fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "Ответ GitHub слишком большой" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
