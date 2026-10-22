package app.lernet.engine.compile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class XhttpNormalizeResult(
    val outbound: JsonObject,
    val remapped: Boolean,
    val note: String?,
)

/**
 * Do not rewrite transport.mode. User `auto` must stay `auto`.
 * stream-one remap caused silent JSON drift.
 *
 * lx.8 applies XMUX even when the section is absent, and that default is
 * max_concurrency 1-1 (xmux.go normalizeXmux). Each TCP flow then opens its
 * own Reality connection. Phone `2e1e0df6073d` climbed pool 2→18 and the
 * extra dials died in awaitRaise at C.TCPTimeout (15s) while downTotal froze
 * at 1950. Stamp a shared pool only when the profile has no xmux of its own.
 *
 * The pool is bounded by connections, not by streams per connection: `max_connections` 2 keeps at most
 * two connections to the server whatever the number of requests (16 streams per connection still grew
 * to ceil(streams/16) connections, each a new handshake). «Один канал» in the settings makes it one.
 * xmux allows only one of `max_connections` and `max_concurrency`, so the stamp sets connections only.
 */
object XhttpMode {
    const val DEFAULT_CHANNELS = 2
    const val SINGLE_CHANNEL = 1
    const val MUX_REQUESTS = "600-900"
    const val MUX_REUSE_SECS = "1800-3000"

    fun channelsRange(channels: Int): String {
        val safe = channels.coerceIn(SINGLE_CHANNEL, DEFAULT_CHANNELS)
        return "$safe-$safe"
    }

    fun normalize(outbound: JsonObject, channels: Int = DEFAULT_CHANNELS): XhttpNormalizeResult {
        val transport = outbound["transport"]?.jsonObject ?: return unchanged(outbound)
        val type = transport["type"]?.jsonPrimitive?.contentOrNull
        if (type != "xhttp" && type != "splithttp") return unchanged(outbound)
        val mode = transport["mode"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "unset" }
        val stamped = stampMux(outbound, transport, channels)
        val muxNote = if (stamped == null) "; xmux kept" else "; xmux max_connections=${channelsRange(channels)}"
        return XhttpNormalizeResult(
            outbound = stamped ?: outbound,
            remapped = false,
            note = "xhttp mode $mode preserved$muxNote",
        )
    }

    private fun stampMux(outbound: JsonObject, transport: JsonObject, channels: Int): JsonObject? {
        if (transport["xmux"] != null) return null
        val muxedTransport = JsonObject(transport.toMutableMap().apply { put("xmux", defaultXmux(channels)) })
        return JsonObject(outbound.toMutableMap().apply { put("transport", muxedTransport) })
    }

    private fun defaultXmux(channels: Int): JsonObject = buildJsonObject {
        // Any field set drops the all-or-nothing defaults, so repeat the
        // Xray request/reuse window instead of leaving those ranges at 0.
        put("max_connections", channelsRange(channels))
        put("h_max_request_times", MUX_REQUESTS)
        put("h_max_reusable_secs", MUX_REUSE_SECS)
    }

    private fun unchanged(outbound: JsonObject): XhttpNormalizeResult =
        XhttpNormalizeResult(outbound, false, null)
}
