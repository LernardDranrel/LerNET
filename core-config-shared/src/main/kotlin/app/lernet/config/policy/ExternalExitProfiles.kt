package app.lernet.config.policy

import app.lernet.config.model.ProfileSource
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.PolicyDestinationAddress
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class ExternalExitKind { SOCKS5, HTTP, CORPORATE_INTERFACE }

/** Evidence identifies one Windows adapter. Availability must be verified by the native host on every socket. */
data class VerifiedInterfaceBinding(val guid: String, val name: String, val index: Int) {
    override fun toString(): String = "VerifiedInterfaceBinding(index=$index)"
}

/** Never print this object: the explicit profile export is the only serialization of credentials. */
class ExternalExitRequest(
    val kind: ExternalExitKind,
    val name: String,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val tls: Boolean = false,
    val binding: VerifiedInterfaceBinding? = null,
    val dnsServer: String? = null,
) {
    override fun toString(): String = "ExternalExitRequest(kind=$kind)"
}

/** Shared creation/edit contract. The platform repository owns persistence and stable profile IDs. */
object ExternalExitProfiles {
    const val INTERFACE_FIELD = "lernet_interface"
    const val ANDROID_INTERFACE_UNSUPPORTED =
        "Интерфейс Windows сохранён. На Android этот выход недоступен, и трафик его ветки блокируется."
    private val guidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun fingerprint(profile: TransferProfile): String = MessageDigest.getInstance("SHA-256")
        .digest(Json { encodeDefaults = true }.encodeToString(profile).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun validate(request: ExternalExitRequest): List<String> = buildList {
        if (!text(request.name.trim(), 120, empty = false)) add("Название должно содержать от 1 до 120 символов без управляющих знаков")
        if (request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
            if (request.binding?.let(::normalizeBinding) == null) add("Выберите подтверждённый интерфейс Windows: GUID, имя и номер")
            if (request.dnsServer != null &&
                literalIp(request.dnsServer) == null
            ) {
                add("Для DNS корпоративной сети укажите IPv4 или IPv6 адрес")
            }
        } else {
            if (PolicyDestinationAddress.normalize(request.host) == null) add("Укажите имя сервера или IP без схемы, порта и пути")
            if (request.port !in 1..65535) add("Порт должен быть от 1 до 65535")
            if (!text(request.username, 255) ||
                !text(request.password, 4096)
            ) {
                add("Учётные данные содержат недопустимые символы или слишком длинные")
            }
            if (request.username.isEmpty() && request.password.isNotEmpty()) add("Для пароля нужно указать имя пользователя")
            if (request.kind == ExternalExitKind.HTTP &&
                ':' in request.username
            ) {
                add("Имя пользователя HTTP-прокси не может содержать двоеточие")
            }
            if (request.kind == ExternalExitKind.SOCKS5 &&
                request.username.isNotEmpty() &&
                (request.username.toByteArray(Charsets.UTF_8).size > 255 || request.password.toByteArray(Charsets.UTF_8).size !in 1..255)
            ) {
                add("SOCKS5: имя и непустой пароль должны занимать не больше 255 байт")
            }
            if (request.kind != ExternalExitKind.HTTP && request.tls) add("TLS в этой форме поддерживается только для HTTP-прокси")
        }
    }

    fun build(
        request: ExternalExitRequest,
        profileId: String = UUID.randomUUID().toString(),
        outboundId: String = UUID.randomUUID().toString(),
        existing: TransferProfile? = null,
    ): TransferProfile {
        val errors = validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        require(text(profileId, 256, empty = false) && text(outboundId, 256, empty = false)) {
            "Профиль и выход должны иметь корректные идентификаторы"
        }
        require(existing == null || existing.id == profileId) { "Редактирование должно сохранять идентификатор профиля" }
        val previous = existing?.let {
            requireNotNull(describe(it)) { "Этот выход содержит расширенные настройки. Используйте редактор JSON." }
        }
        require(existing == null || existing.selectedOutboundId == outboundId) { "Редактирование должно сохранять идентификатор выхода" }
        val selectedTag = existing?.outbounds?.single { it.id == outboundId }?.tag ?: "proxy"
        val type = when (request.kind) {
            ExternalExitKind.SOCKS5 -> "socks"
            ExternalExitKind.HTTP -> "http"
            ExternalExitKind.CORPORATE_INTERFACE -> "direct"
        }
        val outbound = buildJsonObject {
            put("type", type)
            put("tag", selectedTag)
            if (request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
                val binding = requireNotNull(normalizeBinding(requireNotNull(request.binding)))
                putJsonObject(INTERFACE_FIELD) {
                    put("guid", binding.guid)
                    put("name", binding.name)
                    put("index", binding.index)
                }
            } else {
                val host = requireNotNull(PolicyDestinationAddress.normalize(request.host))
                put("server", host)
                put("server_port", request.port)
                if (request.kind == ExternalExitKind.SOCKS5) put("version", "5")
                if (request.username.isNotEmpty()) {
                    put("username", request.username)
                    put("password", request.password)
                }
                if (request.tls) {
                    putJsonObject("tls") {
                        put("enabled", true)
                        put("server_name", host)
                    }
                }
            }
        }
        val base = existing ?: TransferProfile(profileId, request.name.trim(), ProfileSource.JSON_PASTE.name, emptyList(), outboundId)
        val corporateDns = if (request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
            buildJsonObject {
                put(
                    "servers",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "udp")
                                put("tag", "corporate-dns")
                                put("server", request.dnsServer?.let(::literalIp) ?: "1.1.1.1")
                                put("detour", selectedTag)
                            }
                        )
                    }
                )
                put("final", "corporate-dns")
            }.toString()
        } else {
            null
        }
        val replacement = TransferOutbound(outboundId, selectedTag, type, outbound.toString())
        val edited = if (existing == null) listOf(replacement) else existing.outbounds.map { if (it.id == outboundId) replacement else it }
        val switchedOwnedDns = if (previous?.kind == ExternalExitKind.CORPORATE_INTERFACE &&
            request.kind == ExternalExitKind.HTTP &&
            ownedCorporateDns(base, selectedTag)
        ) {
            corporateDnsAsTcp(requireNotNull(base.dnsJson))
        } else {
            base.dnsJson
        }
        return base.copy(
            name = request.name.trim(), source = ProfileSource.JSON_PASTE.name, subscriptionUrl = null,
            outbounds = edited, selectedOutboundId = outboundId,
            dnsJson = corporateDns ?: switchedOwnedDns,
            dnsPolicy = if (corporateDns != null) "PROFILE" else base.dnsPolicy,
        )
    }

    fun describe(profile: TransferProfile): ExternalExitRequest? {
        return runCatching {
            val outbound = profile.outbounds.singleOrNull { it.id == profile.selectedOutboundId } ?: return null
            val raw = Json.parseToJsonElement(outbound.singBoxJson) as? JsonObject ?: return null
            if (raw["type"]?.jsonPrimitive?.contentOrNull != outbound.type ||
                raw["tag"]?.jsonPrimitive?.contentOrNull?.let { it != outbound.tag } == true
            ) {
                return null
            }
            val kind = when (raw["type"]?.jsonPrimitive?.contentOrNull) {
                "socks" -> if (raw["version"]?.jsonPrimitive?.contentOrNull in setOf(null, "5")) ExternalExitKind.SOCKS5 else return null
                "http" -> ExternalExitKind.HTTP
                "direct" -> if (INTERFACE_FIELD in raw) ExternalExitKind.CORPORATE_INTERFACE else return null
                else -> return null
            }
            val binding = if (kind == ExternalExitKind.CORPORATE_INTERFACE) binding(raw) else null
            if (kind == ExternalExitKind.CORPORATE_INTERFACE && !ownedCorporateDns(profile, outbound.tag)) return null
            val fields = when (kind) {
                ExternalExitKind.SOCKS5 -> setOf("type", "tag", "server", "server_port", "version", "username", "password")
                ExternalExitKind.HTTP -> setOf("type", "tag", "server", "server_port", "username", "password", "tls")
                ExternalExitKind.CORPORATE_INTERFACE -> setOf("type", "tag", INTERFACE_FIELD)
            }
            if (raw.keys.any { it !in fields }) return null
            val tls = raw["tls"] as? JsonObject
            if ("tls" in raw && tls == null) return null
            if (tls != null &&
                (
                    tls.keys.any { it !in setOf("enabled", "server_name") } ||
                        tls["enabled"]?.jsonPrimitive?.let { it.isString || it.booleanOrNull == null } == true ||
                        tls["server_name"]?.jsonPrimitive?.contentOrNull?.let { it != raw["server"]?.jsonPrimitive?.contentOrNull } == true
                    )
            ) {
                return null
            }
            if (kind != ExternalExitKind.CORPORATE_INTERFACE &&
                (
                    raw["server"]?.jsonPrimitive?.isString != true ||
                        raw["server_port"]?.jsonPrimitive?.isString != false ||
                        listOf("username", "password").any { it in raw && raw[it]?.jsonPrimitive?.isString != true }
                    )
            ) {
                return null
            }
            val dns = profile.dnsJson?.let { Json.parseToJsonElement(it) as? JsonObject }
            val servers = dns?.get("servers") as? JsonArray
            val request = ExternalExitRequest(
                kind, profile.name, raw["server"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                raw["server_port"]?.jsonPrimitive?.intOrNull ?: 0, raw["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                raw["password"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (raw["tls"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.contentOrNull == "true", binding,
                servers?.firstOrNull()?.let { (it as? JsonObject)?.get("server")?.jsonPrimitive?.contentOrNull }
            )
            request.takeIf { validate(it).isEmpty() }
        }.getOrNull()
    }

    fun binding(profile: TransferProfile): VerifiedInterfaceBinding? {
        return try {
            val outbound = profile.outbounds.singleOrNull { it.id == profile.selectedOutboundId } ?: return null
            val raw = Json.parseToJsonElement(outbound.singBoxJson) as? JsonObject ?: return null
            binding(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("Профиль содержит некорректные данные интерфейсного выхода")
        }
    }

    fun platformRequirement(profile: TransferProfile): RoutePlatform? {
        return try {
            val byTag = profile.outbounds.associateBy { it.tag }
            val selected = profile.outbounds.singleOrNull { it.id == profile.selectedOutboundId } ?: return null
            val pending = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            pending += selected.tag
            while (pending.isNotEmpty()) {
                val tag = pending.removeFirst()
                if (!visited.add(tag)) continue
                require(visited.size <= 10_000) { "Слишком много внутренних выходов" }
                val source = byTag[tag] ?: continue
                val raw = Json.parseToJsonElement(source.singBoxJson) as? JsonObject ?: continue
                if (binding(raw) != null) return RoutePlatform.WINDOWS
                (raw["detour"] as? JsonPrimitive)?.contentOrNull?.let { pending += it }
                (raw["outbounds"] as? JsonArray).orEmpty().forEach { pending += it.jsonPrimitive.content }
            }
            null
        } catch (_: Exception) {
            throw IllegalArgumentException("Не удалось прочитать возможности профиля. Проверьте JSON внутренних выходов.")
        }
    }

    fun binding(raw: JsonObject): VerifiedInterfaceBinding? {
        val element = raw[INTERFACE_FIELD] ?: return null
        val value = element as? JsonObject ?: error("Некорректные данные подтверждённого интерфейса")
        require(raw["type"]?.jsonPrimitive?.contentOrNull == "direct") { "Интерфейсный выход должен быть прямым транспортом" }
        require(value.keys == setOf("guid", "name", "index")) { "Неподдерживаемые данные подтверждённого интерфейса" }
        require(
            value["guid"]?.jsonPrimitive?.isString == true &&
                value["name"]?.jsonPrimitive?.isString == true &&
                value["index"]?.jsonPrimitive?.isString == false
        ) { "Идентичность интерфейса имеет неверные типы полей" }
        return normalizeBinding(
            VerifiedInterfaceBinding(
                value["guid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                value["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), value["index"]?.jsonPrimitive?.intOrNull ?: 0
            )
        )
            ?: error("Некорректная идентичность подтверждённого интерфейса")
    }

    fun normalizeBinding(binding: VerifiedInterfaceBinding): VerifiedInterfaceBinding? {
        val guid = binding.guid.trim().removeSurrounding("{", "}")
        if (!guidPattern.matches(guid) ||
            guid.all { it == '0' || it == '-' } ||
            !text(binding.name, 256, empty = false) ||
            binding.index <= 0
        ) {
            return null
        }
        return binding.copy(guid = guid.lowercase())
    }

    private fun text(value: String, max: Int, empty: Boolean = true) = value.length <= max &&
        (empty || value.isNotBlank()) &&
        value.none { it.isISOControl() }

    private fun ownedCorporateDns(profile: TransferProfile, selectedTag: String): Boolean {
        return runCatching {
            if (profile.dnsPolicy != "PROFILE") return false
            val dns = Json.parseToJsonElement(profile.dnsJson ?: return false) as? JsonObject ?: return false
            if (dns.keys != setOf("servers", "final") || dns["final"]?.jsonPrimitive?.contentOrNull != "corporate-dns") return false
            val server = (dns["servers"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return false
            server.keys == setOf("type", "tag", "server", "detour") &&
                server["type"]?.jsonPrimitive?.contentOrNull == "udp" &&
                server["tag"]?.jsonPrimitive?.contentOrNull == "corporate-dns" &&
                server["detour"]?.jsonPrimitive?.contentOrNull == selectedTag &&
                server["server"]?.jsonPrimitive?.contentOrNull?.let(::literalIp) != null
        }.getOrDefault(false)
    }

    private fun corporateDnsAsTcp(raw: String): String {
        val dns = Json.parseToJsonElement(raw) as JsonObject
        val server = (dns.getValue("servers") as JsonArray).single() as JsonObject
        return JsonObject(dns + ("servers" to JsonArray(listOf(JsonObject(server + ("type" to JsonPrimitive("tcp"))))))).toString()
    }

    private fun literalIp(value: String): String? = PolicyDestinationAddress.normalize(value)?.takeIf {
        ':' in it || Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+").matches(it)
    }
}
