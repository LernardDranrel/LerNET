package app.lernet.engine.policy

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Session memory only. Prefixes are bounded observations, never complete request bodies. */
data class FlowTransfer(val sequence: Long, val atMs: Long, val upload: Boolean, val bytes: Long)
data class FlowInspection(
    val uploadPrefix: String = "",
    val downloadPrefix: String = "",
    val transfers: List<FlowTransfer> = emptyList(),
    val transferCount: Long = 0,
    val payloadAvailable: Boolean = false,
) {
    fun bytes(upload: Boolean): ByteArray = decode(if (upload) uploadPrefix else downloadPrefix) ?: byteArrayOf()
    fun encrypted(protocol: String?): Boolean = protocol?.lowercase() in setOf("tls", "quic", "https") ||
        listOf(bytes(true), bytes(false)).any { it.size >= 3 && (it[0].toInt() and 255) in 20..23 && it[1] == 3.toByte() }
    fun text(upload: Boolean): String = bytes(upload).toString(Charsets.UTF_8).map {
        if (it == '\n' || it == '\r' || it == '\t' || !it.isISOControl()) it else '·'
    }.joinToString("")
    fun hex(upload: Boolean): String = bytes(upload).toList().chunked(16).joinToString("\n") { row ->
        row.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        const val PREFIX_LIMIT = 512
        const val TRANSFER_LIMIT = 8
        fun fromNative(flow: JsonObject): FlowInspection? {
            val row = flow["inspection"] as? JsonObject ?: return null
            fun number(key: String): Long? = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            fun prefix(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { decode(it) != null }.orEmpty()
            val count = number("transfer_count")?.takeIf { it >= 0 } ?: return null
            val transfers = (row["transfers"] as? JsonArray).orEmpty().takeLast(TRANSFER_LIMIT).mapNotNull { item ->
                val entry = item as? JsonObject ?: return@mapNotNull null
                fun value(key: String) = (entry[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                val sequence = value("sequence")?.takeIf { it in 1..count } ?: return@mapNotNull null
                val at = value("at_ms")?.takeIf { it > 0 } ?: return@mapNotNull null
                val size = value("bytes")?.takeIf { it > 0 && it <= Int.MAX_VALUE } ?: return@mapNotNull null
                val upload = (entry["upload"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: return@mapNotNull null
                FlowTransfer(sequence, at, upload, size)
            }.distinctBy { it.sequence }.sortedByDescending { it.sequence }
            return FlowInspection(
                prefix("upload_prefix"), prefix("download_prefix"), transfers, count,
                (row["payload_available"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
            )
        }
        private fun decode(value: String): ByteArray? {
            if (value.length > 684) return null
            return runCatching { Base64.getDecoder().decode(value) }.getOrNull()?.takeIf { it.size <= PREFIX_LIMIT }
        }
    }
}
