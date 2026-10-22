package app.lernet.engine.compile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Reality key exchange offered in ClientHello (core option `reality.key_share`). */
enum class RealityKeyShare(val storage: String) {
    /** As the fingerprint carries it (core default). */
    AUTO(""),
    /** X25519MLKEM768 required. */
    HYBRID("hybrid"),
    /** Short ClientHello without ML-KEM; only Xray servers older than 26.9.8 accept it. */
    CLASSICAL("classical"),
    ;

    companion object {
        fun fromStorage(raw: String?): RealityKeyShare = entries.firstOrNull { it.storage == raw || it.name == raw } ?: AUTO
    }
}

/**
 * Handshake shape of every TLS outbound, applied when the config is assembled (so profiles imported before
 * the parser fix get it too):
 * - no `utls` or no fingerprint → `firefox` = Firefox 148 in our core, with the X25519MLKEM768 key share
 *   (Reality cannot start without uTLS). Chrome 133, the old default, hangs handshakes on several Russian
 *   networks since 2026-09 while Firefox passes (field reports, TSPU-update-2026-10). Plain TLS without
 *   Reality gets it too: Go's own TLS hello is a rare non-browser shape, and LerNET then shows one
 *   browser shape for every TLS outbound;
 * - opt-in `record_fragment`: the first TLS message is split into several TLS records;
 * - opt-in Reality `key_share`.
 * Values written in the profile itself win.
 */
object TlsHandshake {
    const val DEFAULT_FINGERPRINT = "firefox"

    /** Fingerprints offered in the config editors; "" = [DEFAULT_FINGERPRINT]. Others typed in a profile are kept. */
    val CHOICES = listOf("", "firefox", "chrome", "safari", "edge", "ios", "android", "random")

    /** TCP-TLS outbounds; QUIC-based ones (hysteria2, tuic) have no uTLS or record fragmentation. */
    private val TCP_TLS_TYPES = setOf("vless", "vmess", "trojan", "anytls", "http")

    fun apply(outbound: JsonObject, defaults: EngineDefaults): JsonObject {
        val type = outbound["type"]?.jsonPrimitive?.contentOrNull
        if (type !in TCP_TLS_TYPES) return outbound
        val tls = outbound["tls"] as? JsonObject ?: return outbound
        if (tls["enabled"]?.jsonPrimitive?.contentOrNull == "false") return outbound
        val next = tls.toMutableMap()
        val utls = tls["utls"] as? JsonObject
        val utlsOn = utls != null && utls["enabled"]?.jsonPrimitive?.contentOrNull != "false"
        if (!utlsOn) {
            next["utls"] = JsonObject(mapOf("enabled" to JsonPrimitive(true), "fingerprint" to JsonPrimitive(DEFAULT_FINGERPRINT)))
        } else if (utls?.get("fingerprint")?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            next["utls"] = JsonObject(utls!!.toMutableMap().apply { put("fingerprint", JsonPrimitive(DEFAULT_FINGERPRINT)) })
        }
        if (defaults.tlsRecordFragment && "record_fragment" !in tls && "fragment" !in tls) {
            next["record_fragment"] = JsonPrimitive(true)
        }
        val reality = tls["reality"] as? JsonObject
        val keyShare = RealityKeyShare.fromStorage(defaults.realityKeyShare)
        if (reality != null && keyShare != RealityKeyShare.AUTO && "key_share" !in reality) {
            next["reality"] = JsonObject(reality.toMutableMap().apply { put("key_share", JsonPrimitive(keyShare.storage)) })
        }
        return JsonObject(outbound.toMutableMap().apply { put("tls", JsonObject(next)) })
    }
}
