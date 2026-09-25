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
 */
object XhttpMode {
    const val MUX_CONCURRENCY = "16-16"
    const val MUX_REQUESTS = "600-900"
    const val MUX_REUSE_SECS = "1800-3000"

    fun normalize(outbound: JsonObject, concurrency: String = MUX_CONCURRENCY): XhttpNormalizeResult {
        val transport = outbound["transport"]?.jsonObject ?: return unchanged(outbound)
        val type = transport["type"]?.jsonPrimitive?.contentOrNull
        if (type != "xhttp" && type != "splithttp") return unchanged(outbound)
        val mode = transport["mode"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "unset" }
        val stamped = stampMux(outbound, transport, concurrency)
        val muxNote = if (stamped == null) "; xmux kept" else "; xmux max_concurrency=$concurrency"
        return XhttpNormalizeResult(
            outbound = stamped ?: outbound,
            remapped = false,
            note = "xhttp mode $mode preserved$muxNote",
        )
    }

    private fun stampMux(outbound: JsonObject, transport: JsonObject, concurrency: String): JsonObject? {
        if (transport["xmux"] != null) return null
        val muxedTransport = JsonObject(transport.toMutableMap().apply { put("xmux", defaultXmux(concurrency)) })
        return JsonObject(outbound.toMutableMap().apply { put("transport", muxedTransport) })
    }

    private fun defaultXmux(concurrency: String): JsonObject = buildJsonObject {
        // Any field set drops the all-or-nothing defaults, so repeat the
        // Xray request/reuse window instead of leaving those ranges at 0.
        put("max_concurrency", concurrency)
        put("h_max_request_times", MUX_REQUESTS)
        put("h_max_reusable_secs", MUX_REUSE_SECS)
    }

    private fun unchanged(outbound: JsonObject): XhttpNormalizeResult =
        XhttpNormalizeResult(outbound, false, null)
}
