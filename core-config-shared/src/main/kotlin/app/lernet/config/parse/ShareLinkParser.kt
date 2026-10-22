package app.lernet.config.parse

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Share links of every protocol LerNET connects with: vless, vmess, trojan, shadowsocks, hysteria2 and tuic.
 * A link that cannot be used as it is fails with a plain reason; nothing in it is silently changed.
 *
 * The TLS fingerprint is stored only when the link carries `fp`: the assembler fills in the current default
 * ([app.lernet.engine.compile.TlsHandshake]), so a later change of the default reaches these configs too.
 */
object ShareLinkParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Schemes this parser accepts, lower case, without `://`. */
    val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic")

    /** True when [line] starts with one of [SCHEMES]. */
    fun isShareLink(line: String): Boolean = scheme(line) != null

    fun scheme(line: String): String? {
        val head = line.trim().substringBefore("://", "").lowercase()
        return head.takeIf { line.contains("://") && it in SCHEMES }
    }

    /** Finds share links anywhere in [text] (one per line or separated by spaces). */
    fun countLinks(text: String): Int = Regex("(?i)\\b(${SCHEMES.joinToString("|")})://").findAll(text).count()

    fun parse(rawLink: String): ImportResult {
        val link = rawLink.trim()
        if (link.isEmpty()) return fail("link", "Пустая ссылка")
        return when (scheme(link)) {
            "vless" -> VlessParser.parse(link)
            "vmess" -> guarded { parseVmess(link) }
            "trojan" -> guarded { parseTrojan(link) }
            "ss" -> guarded { parseShadowsocks(link) }
            "hysteria2", "hy2" -> guarded { parseHysteria2(link) }
            "tuic" -> guarded { parseTuic(link) }
            else -> fail("link", "Ссылка этого вида не поддерживается. Подходят vless://, vmess://, trojan://, ss://, hysteria2://, hy2:// и tuic://")
        }
    }

    // ---- vmess ----

    private fun parseVmess(link: String): ImportResult {
        val body = link.substringAfter("://")
        // Newer clients share vmess like vless: vmess://uuid@host:port?…#name.
        if ('@' in body.substringBefore('#').substringBefore('?')) return parseVmessUri(link)
        val decoded = decodeBase64(body.substringBefore('#'))
            ?: return fail("link", "Ссылку vmess:// не удалось прочитать: внутри не Base64")
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: return fail("link", "Ссылку vmess:// не удалось прочитать: внутри не JSON")
        fun field(name: String): String = obj[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val host = field("add")
        val uuid = field("id")
        if (host.isEmpty()) return fail("server", "В ссылке нет адреса сервера")
        if (uuid.isEmpty()) return fail("uuid", "В ссылке нет UUID")
        val port = portOf(field("port")) ?: return fail("port", "В ссылке неверный порт")
        val params = mutableMapOf<String, String>()
        params["type"] = field("net").ifEmpty { "tcp" }
        params["headertype"] = field("type")
        params["host"] = field("host")
        params["path"] = field("path")
        params["servicename"] = field("path")
        params["security"] = field("tls")
        params["sni"] = field("sni")
        params["alpn"] = field("alpn")
        params["fp"] = field("fp")
        params["allowinsecure"] = field("allowInsecure")
        params["mode"] = field("mode")
        val name = field("ps").ifEmpty { host }
        return vmessDraft(name, host, port, uuid, field("aid").toIntOrNull() ?: 0, field("scy"), params)
    }

    private fun parseVmessUri(link: String): ImportResult {
        val parts = LinkParts.of(link) ?: return fail("link", "Ссылка не является допустимым адресом")
        val uuid = parts.user
        if (uuid.isEmpty()) return fail("uuid", "В ссылке нет UUID")
        val port = parts.port ?: return fail("port", "В ссылке неверный порт")
        val name = parts.name.ifEmpty { parts.host }
        return vmessDraft(name, parts.host, port, uuid, parts.query["alterid"]?.toIntOrNull() ?: 0,
            parts.query["encryption"].orEmpty(), parts.query)
    }

    private fun vmessDraft(
        name: String, host: String, port: Int, uuid: String, alterId: Int, cipher: String, params: Map<String, String>,
    ): ImportResult {
        val outbound = buildJsonObject {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", cipher.ifBlank { "auto" })
            put("alter_id", alterId)
            putTls(this, host, params, defaultOn = false)?.let { return fail("tls", it) }
            putTransport(this, params)?.let { return fail("transport", it) }
        }
        return success(name, "vmess", outbound)
    }

    // ---- trojan ----

    private fun parseTrojan(link: String): ImportResult {
        val parts = LinkParts.of(link) ?: return fail("link", "Ссылка не является допустимым адресом")
        if (parts.user.isEmpty()) return fail("password", "В ссылке нет пароля")
        val port = parts.port ?: return fail("port", "В ссылке неверный порт")
        val params = parts.query.toMutableMap()
        if (params["sni"].isNullOrBlank() && !params["peer"].isNullOrBlank()) params["sni"] = params.getValue("peer")
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", parts.host)
            put("server_port", port)
            put("password", parts.user)
            // Trojan is TLS unless the link says otherwise.
            putTls(this, parts.host, params, defaultOn = true)?.let { return fail("tls", it) }
            putTransport(this, params)?.let { return fail("transport", it) }
        }
        return success(parts.name.ifEmpty { parts.host }, "trojan", outbound)
    }

    // ---- shadowsocks ----

    private val SS_METHODS = setOf(
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "none", "plain",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "rc4-md5", "chacha20-ietf", "xchacha20",
    )

    private fun parseShadowsocks(link: String): ImportResult {
        val withoutName = link.substringBefore('#')
        val name = link.substringAfter('#', "").let(::decode).trim()
        val body = withoutName.substringAfter("://")
        // Legacy form: ss://base64(method:password@host:port)
        val expanded = if ('@' !in body.substringBefore('?')) {
            val decoded = decodeBase64(body.substringBefore('?').substringBefore('/'))
                ?: return fail("link", "Ссылку ss:// не удалось прочитать")
            "ss://" + decoded + body.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        } else withoutName
        val parts = LinkParts.of(expanded) ?: return fail("link", "Ссылка не является допустимым адресом")
        val port = parts.port ?: return fail("port", "В ссылке неверный порт")
        // SIP002: the user part is base64url(method:password), or percent-encoded method:password (2022 methods).
        val rawUser = parts.rawUser
        val credentials = decode(rawUser).takeIf { it.contains(':') } ?: decodeBase64(decode(rawUser))
            ?: return fail("password", "В ссылке не удалось прочитать метод и пароль")
        val method = credentials.substringBefore(':').trim().lowercase()
        val password = credentials.substringAfter(':', "")
        if (method.isEmpty() || password.isEmpty() && method !in setOf("none", "plain")) {
            return fail("password", "В ссылке нет метода шифрования или пароля")
        }
        if (method !in SS_METHODS) return fail("method", "Метод шифрования «$method» не поддерживается")
        val plugin = parts.query["plugin"].orEmpty()
        val (pluginName, pluginOpts) = when {
            plugin.isBlank() -> null to null
            else -> {
                val name0 = plugin.substringBefore(';').trim()
                val opts = plugin.substringAfter(';', "")
                when (name0) {
                    "obfs-local", "simple-obfs" -> "obfs-local" to opts
                    "v2ray-plugin" -> "v2ray-plugin" to opts
                    else -> return fail("plugin", "Дополнение «$name0» для Shadowsocks не поддерживается")
                }
            }
        }
        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", parts.host)
            put("server_port", port)
            put("method", if (method == "plain") "none" else method)
            put("password", password)
            pluginName?.let { put("plugin", it) }
            pluginOpts?.takeIf { it.isNotBlank() }?.let { put("plugin_opts", it) }
        }
        return success(name.ifEmpty { parts.host }, "shadowsocks", outbound)
    }

    // ---- hysteria2 ----

    private fun parseHysteria2(link: String): ImportResult {
        val parts = LinkParts.of(link, multiPort = true) ?: return fail("link", "Ссылка не является допустимым адресом")
        if (parts.rawUser.isEmpty()) return fail("password", "В ссылке нет пароля")
        val port = parts.port ?: return fail("port", "В ссылке неверный порт")
        val q = parts.query
        val unused = mutableListOf<String>()
        val hopping = (parts.portRanges + q["mport"].orEmpty().split(',').mapNotNull(::range)).distinct()
        val outbound = buildJsonObject {
            put("type", "hysteria2")
            put("tag", "proxy")
            put("server", parts.host)
            put("server_port", port)
            if (hopping.isNotEmpty()) put("server_ports", JsonArray(hopping.map(::JsonPrimitive)))
            // The whole user part is the password; "user:pass" stays as one string, as in the official client.
            put("password", decode(parts.rawUser))
            when (val obfs = q["obfs"].orEmpty().lowercase()) {
                "" , "none" -> Unit
                "salamander" -> putJsonObject("obfs") {
                    put("type", "salamander")
                    put("password", q["obfs-password"].orEmpty())
                }
                else -> return fail("obfs", "Маскировка «$obfs» для Hysteria2 не поддерживается")
            }
            putJsonObject("tls") {
                put("enabled", true)
                put("server_name", q["sni"].orEmpty().ifBlank { parts.host })
                if (truthy(q["insecure"])) put("insecure", true)
                alpnOf(q["alpn"].orEmpty().ifBlank { "h3" })?.let { put("alpn", it) }
            }
        }
        if (!q["pinsha256"].isNullOrBlank()) {
            unused += "закрепление сертификата (pinSHA256) — не поддерживается, сертификат проверяется обычным способом" +
                if (truthy(q["insecure"])) "; проверка сертификата в ссылке выключена" else ""
        }
        return success(parts.name.ifEmpty { parts.host }, "hysteria2", outbound, unused)
    }

    // ---- tuic ----

    private fun parseTuic(link: String): ImportResult {
        val parts = LinkParts.of(link) ?: return fail("link", "Ссылка не является допустимым адресом")
        val credentials = decode(parts.rawUser)
        val uuid = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        if (uuid.isEmpty()) return fail("uuid", "В ссылке нет UUID")
        val port = parts.port ?: return fail("port", "В ссылке неверный порт")
        val q = parts.query
        val outbound = buildJsonObject {
            put("type", "tuic")
            put("tag", "proxy")
            put("server", parts.host)
            put("server_port", port)
            put("uuid", uuid)
            if (password.isNotEmpty()) put("password", password)
            (q["congestion_control"] ?: q["congestion"])?.takeIf { it.isNotBlank() }?.let { put("congestion_control", it) }
            q["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { put("udp_relay_mode", it) }
            if (truthy(q["reduce_rtt"]) || truthy(q["zero_rtt_handshake"])) put("zero_rtt_handshake", true)
            putJsonObject("tls") {
                put("enabled", true)
                put("server_name", q["sni"].orEmpty().ifBlank { parts.host })
                if (truthy(q["allow_insecure"]) || truthy(q["insecure"]) || truthy(q["allowinsecure"])) put("insecure", true)
                if (truthy(q["disable_sni"])) put("disable_sni", true)
                alpnOf(q["alpn"].orEmpty().ifBlank { "h3" })?.let { put("alpn", it) }
            }
        }
        return success(parts.name.ifEmpty { parts.host }, "tuic", outbound)
    }

    // ---- shared pieces ----

    /** TLS block for TCP protocols. Returns a reason when the link asks for something unsupported. */
    private fun putTls(builder: JsonObjectBuilder, host: String, q: Map<String, String>, defaultOn: Boolean): String? {
        val security = q["security"].orEmpty().lowercase().ifBlank { if (defaultOn) "tls" else "none" }
        when (security) {
            "none", "" -> return null
            "tls", "reality" -> Unit
            else -> return "Защита «$security» не поддерживается"
        }
        builder.putJsonObject("tls") {
            put("enabled", true)
            put("server_name", q["sni"].orEmpty().ifBlank { q["host"].orEmpty().takeIf { security == "tls" && it.isNotBlank() && ',' !in it } ?: host })
            if (truthy(q["allowinsecure"]) || truthy(q["insecure"])) put("insecure", true)
            alpnOf(q["alpn"].orEmpty())?.let { put("alpn", it) }
            val fp = q["fp"]?.trim()?.lowercase().orEmpty()
            if (fp.isNotEmpty() || security == "reality") {
                putJsonObject("utls") {
                    put("enabled", true)
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                }
            }
            if (security == "reality") {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", q["pbk"].orEmpty())
                    put("short_id", q["sid"].orEmpty())
                }
            }
        }
        return null
    }

    /** Transport block. Returns a reason when the link asks for a transport the core does not have. */
    private fun putTransport(builder: JsonObjectBuilder, q: Map<String, String>): String? {
        val type = q["type"].orEmpty().lowercase()
        when (type) {
            "", "tcp", "raw" -> {
                val header = q["headertype"].orEmpty().lowercase()
                if (header.isNotEmpty() && header != "none") return "Маскировка TCP «$header» не поддерживается"
            }
            "ws", "websocket" -> builder.putJsonObject("transport") {
                put("type", "ws")
                val rawPath = q["path"].orEmpty().ifBlank { "/" }
                val early = Regex("[?&]ed=(\\d+)").find(rawPath)?.groupValues?.get(1)?.toIntOrNull()
                val path = if (early != null) rawPath.replace(Regex("[?&]ed=\\d+"), "").ifBlank { "/" } else rawPath
                put("path", path)
                if (early != null) {
                    put("max_early_data", early)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
                q["host"]?.takeIf { it.isNotBlank() }?.let { host -> putJsonObject("headers") { put("Host", host) } }
            }
            "grpc" -> builder.putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", q["servicename"].orEmpty())
            }
            "http", "h2" -> builder.putJsonObject("transport") {
                put("type", "http")
                put("path", q["path"].orEmpty().ifBlank { "/" })
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", JsonArray(it.split(',').map { h -> JsonPrimitive(h.trim()) })) }
            }
            "httpupgrade" -> builder.putJsonObject("transport") {
                put("type", "httpupgrade")
                put("path", q["path"].orEmpty().ifBlank { "/" })
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            "xhttp", "splithttp" -> builder.putJsonObject("transport") {
                put("type", "xhttp")
                put("path", q["path"].orEmpty().ifBlank { "/" })
                q["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                put("mode", q["mode"].orEmpty().ifBlank { "auto" })
            }
            else -> return "Транспорт «$type» не поддерживается"
        }
        return null
    }

    private fun alpnOf(raw: String): JsonArray? =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map(::JsonPrimitive)) }

    private fun truthy(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)

    private fun range(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val from = text.substringBefore('-').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val to = text.substringAfter('-', text).toIntOrNull()?.takeIf { it in from..65535 } ?: return null
        return if (from == to) null else "$from:$to"
    }

    private fun portOf(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    private fun success(name: String, type: String, outbound: JsonObject, unused: List<String> = emptyList()): ImportResult {
        val id = newId()
        return ImportResult.Success(
            listOf(
                ImportedProfileDraft(
                    name = name,
                    // Historical name: VLESS marks «a single pasted link» of any protocol (kept for archive compatibility).
                    source = ProfileSource.VLESS,
                    outbounds = listOf(NormalizedOutbound(id, "proxy", type, outbound.toString())),
                    selectedOutboundId = id,
                    subscriptionUrl = null,
                    unusedParts = unused,
                ),
            ),
        )
    }

    private fun fail(field: String, message: String): ImportResult = ImportResult.Failure(listOf(FieldError(field, message)))

    private inline fun guarded(block: () -> ImportResult): ImportResult =
        runCatching(block).getOrElse { fail("link", "Ссылку не удалось прочитать") }

    internal fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(value)

    internal fun decodeBase64(raw: String): String? {
        val compact = raw.trim().replace("\\s".toRegex(), "")
        if (compact.isEmpty() || !compact.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return null
        val normal = compact.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = normal + "=".repeat((4 - normal.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
            ?.takeIf { text -> text.isNotEmpty() && text.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() } }
    }

    /** scheme://user@host:port/path?query#name, read by hand: ports like "443,5000-6000" or IPv6 hosts break java.net.URI. */
    internal class LinkParts(
        val rawUser: String,
        val host: String,
        val port: Int?,
        val portRanges: List<String>,
        val query: Map<String, String>,
        val name: String,
    ) {
        val user: String get() = decode(rawUser)

        companion object {
            fun of(link: String, multiPort: Boolean = false): LinkParts? {
                val name = link.substringAfter('#', "").let(::decode).trim()
                val noName = link.substringBefore('#')
                val afterScheme = noName.substringAfter("://", "")
                if (afterScheme.isEmpty()) return null
                val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) afterScheme.length else it }
                val authority = afterScheme.substring(0, authorityEnd)
                val rest = afterScheme.substring(authorityEnd)
                val rawQuery = rest.substringAfter('?', "")
                val at = authority.lastIndexOf('@')
                val rawUser = if (at >= 0) authority.substring(0, at) else ""
                val hostPort = if (at >= 0) authority.substring(at + 1) else authority
                val host: String
                val portText: String
                if (hostPort.startsWith("[")) {
                    host = hostPort.substringAfter('[').substringBefore(']')
                    portText = hostPort.substringAfter("]", "").removePrefix(":")
                } else {
                    host = hostPort.substringBefore(':')
                    portText = hostPort.substringAfter(':', "")
                }
                if (host.isBlank()) return null
                val ports = portText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val first = ports.firstOrNull()
                val single = when {
                    first == null -> 443
                    '-' in first -> first.substringBefore('-').toIntOrNull()
                    else -> first.toIntOrNull()
                }?.takeIf { it in 1..65535 }
                if (!multiPort && ports.size > 1) return LinkParts(rawUser, host.trim(), null, emptyList(), emptyMap(), name)
                val ranges = if (multiPort) ports.mapNotNull { ShareLinkParser.range(it) } else emptyList()
                return LinkParts(rawUser, host.trim(), single, ranges, parseQuery(rawQuery), name)
            }

            private fun parseQuery(raw: String): Map<String, String> =
                raw.split('&').mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val key = decode(pair.substringBefore('=')).trim().lowercase()
                    val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                    if (key.isEmpty()) null else key to value
                }.toMap()
        }
    }
}
