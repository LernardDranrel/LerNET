package app.lernet.desktop

import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.engine.policy.expertNativeFailureExplanation
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class ExpertNativeAck(val instanceId: String, val interfaceId: String, val revision: Long)

internal class ExpertControlRejectedException(val status: Int, message: String) : IllegalStateException(message)

/** Local control is authenticated even though the listener is bound to loopback. */
internal class ExpertControlClient(
    address: InetSocketAddress,
    private val bearerToken: String,
    private val connectTimeoutMs: Int = 2_000,
    private val maxResponseBytes: Int = 2_000_000,
) {
    private val baseUrl: String

    init {
        require(!address.isUnresolved && address.address.isLoopbackAddress) { "Управление ядром доступно только через loopback" }
        require(address.port in 1..65535)
        require(bearerToken.length >= 32 && bearerToken.none { it.isWhitespace() || it.isISOControl() })
        require(connectTimeoutMs > 0 && maxResponseBytes > 0)
        val host = address.address.hostAddress
        baseUrl = "http://${if (':' in host) "[$host]" else host}:${address.port}/lernet/v1"
    }

    fun capabilities(): PolicyControlCapabilities {
        val body = request("capabilities")
        val protocol = body["protocol_version"] as? JsonPrimitive
        if (protocol?.isString != false || protocol.intOrNull != 1) return PolicyControlCapabilities.RESTART_ONLY
        return PolicyControlCapabilities(
            body.confirmed("preserves_tun"),
            body.confirmed("hot_policy_apply") && body.confirmed("atomic_prepare_commit") && body.confirmed("destination_redirect"),
            body.confirmed("independent_exit_lifecycle") && body.confirmed("bounded_first_flow_wait"),
            nativeHealthRecovery = body.confirmed("native_health_recovery"),
        )
    }

    fun request(operation: String, payload: JsonObject? = null, timeoutMs: Int = 45_000): JsonObject {
        require(operation in OPERATIONS) { "Неизвестная операция управления ядром" }
        require(timeoutMs in 1..60_000)
        val connection = URL("$baseUrl/$operation").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs.coerceAtMost(timeoutMs)
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) { "План маршрутизации превышает лимит управления ядром" }
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    check(output.size() <= maxResponseBytes - count) { "Ответ ядра превышает допустимый размер" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            val objectBody = runCatching { Json.parseToJsonElement(response) as? JsonObject }.getOrNull()
            if (status !in 200..299) {
                // Never print raw JSON: an error body can contain private configuration data.
                val explanation = (objectBody?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it.length <= 256 && it.none(Char::isISOControl) }
                    ?.replace(bearerToken, "***")?.let(::expertNativeFailureExplanation)
                throw ExpertControlRejectedException(
                    status, "Ядро отклонило операцию ($status)${explanation?.let { ": $it" }.orEmpty()}",
                )
            }
            return requireNotNull(objectBody) { "Ядро вернуло некорректный ответ управления" }
        } finally {
            connection.disconnect()
        }
    }

    fun acknowledgement(body: JsonObject): ExpertNativeAck {
        val instance = (body["instance_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val networkInterface = (body["interface_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val revision = (body["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        check(
            !instance.isNullOrBlank() &&
                !networkInterface.isNullOrBlank() &&
                revision != null &&
                revision >= 0 &&
                instance.length <= 512 &&
                networkInterface.length <= 512 &&
                instance.none { it.isISOControl() } &&
                networkInterface.none { it.isISOControl() }
        ) {
            "Ядро не подтвердило интерфейс и версию схемы"
        }
        return ExpertNativeAck(instance, networkInterface, revision)
    }

    private fun JsonObject.confirmed(field: String): Boolean =
        (get(field) as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    private companion object {
        const val MAX_REQUEST_BYTES = 20_000_000
        val OPERATIONS = setOf(
            "capabilities", "start", "apply", "stop", "status", "probe", "wake", "recover", "sleep", "network_changed",
        )
    }
}
