package app.lernet.config.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

enum class GuessedMode {
    VLESS,
    JSON_PASTE,
    JSON_URL,
    SUBSCRIPTION,
}

enum class SoftHint {
    NONE,
    MAYBE_SUBSCRIPTION,
}

data class ImportGuess(
    val mode: GuessedMode? = null,
    val soft: SoftHint = SoftHint.NONE,
    val needsFetch: Boolean = false,
    val document: Boolean = false,
) {
    companion object {
        val EMPTY = ImportGuess()
    }
}

object ImportHint {
    private val json = Json { ignoreUnknownKeys = true }
    private val vlessToken = Regex("(?i)vless://")

    fun detect(text: String): ImportGuess {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportGuess.EMPTY
        return fromVless(trimmed)
            ?: fromJson(trimmed)
            ?: fromUrl(trimmed)
            ?: fromBase64(trimmed)
            ?: ImportGuess(mode = null, soft = SoftHint.MAYBE_SUBSCRIPTION)
    }

    fun classifyFetched(contentType: String?, body: String): ImportGuess {
        val type = contentType.orEmpty().lowercase()
        val trimmed = body.trim()
        val jsonBody = type.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")
        return if (jsonBody) {
            ImportGuess(GuessedMode.JSON_URL, SoftHint.NONE)
        } else {
            ImportGuess(GuessedMode.SUBSCRIPTION, SoftHint.MAYBE_SUBSCRIPTION)
        }
    }

    fun isHttpUrl(text: String): Boolean {
        val trimmed = text.trim()
        val http = trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        return http && !trimmed.contains('\n') && !trimmed.contains(' ')
    }

    private fun fromVless(text: String): ImportGuess? {
        val count = vlessToken.findAll(text).count()
        if (count == 0) return null
        if (count == 1 && text.startsWith("vless://", ignoreCase = true)) {
            return ImportGuess(GuessedMode.VLESS, SoftHint.NONE)
        }
        if (count > 1) {
            return ImportGuess(GuessedMode.SUBSCRIPTION, SoftHint.NONE, document = true)
        }
        return null
    }

    private fun fromJson(text: String): ImportGuess? {
        if (!text.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val bare = obj["type"] != null
        val outbounds = obj["outbounds"] is JsonArray
        if (!bare && !outbounds) return null
        return ImportGuess(GuessedMode.JSON_PASTE, SoftHint.NONE)
    }

    private fun fromUrl(text: String): ImportGuess? {
        if (!isHttpUrl(text)) return null
        val path = text.substringBefore('?').substringBefore('#')
        if (path.endsWith(".json", ignoreCase = true)) {
            return ImportGuess(GuessedMode.JSON_URL, SoftHint.NONE)
        }
        return ImportGuess(mode = null, soft = SoftHint.NONE, needsFetch = true)
    }

    private fun fromBase64(text: String): ImportGuess? {
        val decoded = SubscriptionParser.decodeMaybeBase64(text)
        if (decoded == text || !decoded.contains("vless://", ignoreCase = true)) return null
        return ImportGuess(GuessedMode.SUBSCRIPTION, SoftHint.NONE, document = true)
    }
}
