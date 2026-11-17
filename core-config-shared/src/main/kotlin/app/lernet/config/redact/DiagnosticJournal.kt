package app.lernet.config.redact

import java.io.IOException

/**
 * Admission of one automatic journal line.
 * In: a known level, a known event, and only fields the caller already has.
 * Out: the line to write, or null when a repeated state is held.
 * A stored level outside info/debug/error is warn, the same rule as the existing setting help.
 * An unknown event or an unknown wire key is refused: nothing from that line is written.
 */
enum class DiagLevel { WARN, INFO, DEBUG }

enum class DiagKind { EDGE, STATE }

enum class DiagEvent {
    NET_REASON,
    NET_WATCH,
    NET_EPOCH,
    UNDERLAY_CALLBACK,
    UNDERLAY_WAIT,
    UNDERLAY_BOUND,
    START_RETRY,
    HEALTH_GATE,
    CORE_RECONNECT,
    LINK_DECISION,
    FOLDER_SELECT,
    FALLBACK,
    START_RESULT,
    START_CANCEL,
    PROCESS_EXIT,
    STDOUT_BROKEN,
    DNS_DECISION,
    PROBE_RESULT,
    FLOW_KEEP,
    HOP_CHECK,
    GUARD,
    IMPORT_RESULT,
    SUB_REFRESH,
    UPDATE_RESULT,
    PROXY_CRED,
    WRITER_FAIL,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): DiagEvent? = entries.firstOrNull { it.wire == value }
    }
}

data class DiagFact(
    val event: DiagEvent,
    val kind: DiagKind,
    val stage: String? = null,
    val outcome: String? = null,
    val code: String? = null,
    val run: String? = null,
    val session: String? = null,
    val attempt: Long? = null,
    val state: String? = null,
    val srcMs: Long? = null,
    val rcvMs: Long? = null,
    val monoMs: Long? = null,
    val domain: String? = null,
    val ip: String? = null,
    val keepAddress: Boolean = false,
)

object DiagnosticLevels {
    @Volatile var current: DiagLevel = DiagLevel.WARN

    fun apply(stored: String): DiagLevel {
        val level = when (stored) {
            "debug" -> DiagLevel.DEBUG
            "info" -> DiagLevel.INFO
            "warn", "error" -> DiagLevel.WARN
            else -> DiagLevel.WARN
        }
        current = level
        return level
    }
}

class DiagnosticJournal {
    private val lastAt = HashMap<Key, Long>()
    private val held = HashMap<Group, Int>()
    private var drops = 0

    fun noteDrop() {
        drops++
    }

    /** Null means the line is a repeated state and was counted on its group. */
    fun admit(level: DiagLevel, fact: DiagFact, nowMs: Long): String? {
        val key = fact.key()
        val group = fact.group()
        val previous = lastAt[key]
        if (fact.kind == DiagKind.STATE && previous != null && nowMs - previous < WINDOW_MS) {
            held[group] = (held[group] ?: 0) + 1
            return null
        }
        val skip = held.remove(group) ?: 0
        val dropped = drops.also { drops = 0 }
        lastAt[key] = nowMs
        return render(level, fact, skip, dropped)
    }

    fun incoming(line: String, nowMs: Long): String? {
        val trimmed = line.trim()
        parseNetwork(trimmed)?.let { return admit(DiagnosticLevels.current, it.copy(rcvMs = nowMs), nowMs) }
        if (trimmed.startsWith("LERNET_DIAG ")) {
            val fact = parseDiag(trimmed) ?: return renderRejected(nowMs)
            return admit(DiagnosticLevels.current, fact.copy(rcvMs = fact.rcvMs ?: nowMs), nowMs)
        }
        return text(DiagnosticLevels.current, trimmed, keepAddress = false, nowMs = nowMs)
    }

    fun text(level: DiagLevel, raw: String, keepAddress: Boolean, nowMs: Long): String {
        val dropped = drops.also { drops = 0 }
        val body = privacy(level, raw, keepAddress)
        val drop = if (dropped > 0) " dropped=$dropped" else ""
        return "rcv=$nowMs$drop $body"
    }

    companion object {
        const val WINDOW_MS = 10_000L
        val process = DiagnosticJournal()

        fun writerFailureKillsProcess(error: Throwable): Boolean = error !is IOException

        fun wire(event: DiagEvent, kind: DiagKind, vararg fields: Pair<String, String?>): String {
            val kindWire = if (kind == DiagKind.EDGE) "edge" else "state"
            val body = fields.mapNotNull { (key, value) ->
                if (value == null) null else "$key=${token(value)}"
            }.joinToString(" ")
            val tail = if (body.isEmpty()) "" else " $body"
            return "LERNET_DIAG ev=${event.wire} kind=$kindWire$tail"
        }
    }
}

private data class Group(val event: DiagEvent, val run: String?, val session: String?, val attempt: Long?)

private data class Key(
    val group: Group,
    val stage: String?,
    val outcome: String?,
    val code: String?,
    val state: String?,
    val domain: String?,
    val ip: String?,
)

private fun DiagFact.group() = Group(event, run, session, attempt)

private fun DiagFact.key() = Key(group(), stage, outcome, code, state, domain, ip)

private val wireKeys = setOf(
    "ev", "kind", "stage", "outcome", "code", "run", "session", "attempt",
    "state", "src", "mono", "domain", "ip", "keep",
)

private fun parseNetwork(line: String): DiagFact? {
    if (!line.startsWith("LERNET_NETWORK ")) return null
    val reason = line.substringAfter("reason=", missingDelimiterValue = "").substringBefore(' ')
    if (!line.contains("reason=") || !tokenOk(reason, allowEmpty = true)) return null
    return DiagFact(DiagEvent.NET_REASON, DiagKind.EDGE, state = reason.ifEmpty { null })
}

private fun parseDiag(line: String): DiagFact? {
    val parts = line.removePrefix("LERNET_DIAG ").split(' ').filter { it.isNotEmpty() }
    val values = HashMap<String, String>()
    for (part in parts) {
        val cut = part.indexOf('=')
        if (cut <= 0) return null
        val key = part.substring(0, cut)
        val value = part.substring(cut + 1)
        if (key !in wireKeys || key in values || !tokenOk(value, allowEmpty = false)) return null
        values[key] = value
    }
    val event = values["ev"]?.let(DiagEvent::fromWire) ?: return null
    val kind = when (values["kind"]) {
        "edge" -> DiagKind.EDGE
        "state" -> DiagKind.STATE
        else -> return null
    }
    val attempt = values["attempt"]?.toLongOrNull() ?: if ("attempt" in values) return null else null
    val src = values["src"]?.toLongOrNull() ?: if ("src" in values) return null else null
    val mono = values["mono"]?.toLongOrNull() ?: if ("mono" in values) return null else null
    return DiagFact(
        event = event,
        kind = kind,
        stage = absent(values["stage"]),
        outcome = absent(values["outcome"]),
        code = absent(values["code"]),
        run = absent(values["run"]),
        session = absent(values["session"]),
        attempt = attempt,
        state = absent(values["state"]),
        srcMs = src,
        monoMs = mono,
        domain = absent(values["domain"]),
        ip = absent(values["ip"]),
        keepAddress = values["keep"] == "1",
    )
}

private fun absent(value: String?): String? = value?.takeUnless { it == "-" }

private fun token(value: String): String = if (tokenOk(value, allowEmpty = false)) value else "unlisted"

private fun tokenOk(value: String, allowEmpty: Boolean): Boolean {
    if (value.isEmpty()) return allowEmpty
    return value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == ':' }
}

private fun render(level: DiagLevel, fact: DiagFact, skip: Int, dropped: Int): String = buildString {
    append("event=${fact.event.wire} kind=${if (fact.kind == DiagKind.EDGE) "edge" else "state"}")
    field("stage", fact.stage)
    field("outcome", fact.outcome)
    field("code", fact.code)
    field("state", fact.state)
    field("run", fact.run)
    field("session", fact.session)
    if (fact.attempt != null) append(" attempt=${fact.attempt}")
    if (fact.srcMs != null) append(" src=${fact.srcMs}")
    if (fact.rcvMs != null) append(" rcv=${fact.rcvMs}")
    if (fact.monoMs != null) append(" mono=${fact.monoMs}")
    if (fact.keepAddress || level != DiagLevel.WARN) {
        field("domain", fact.domain)
        field("ip", fact.ip)
    }
    if (skip > 0) append(" skip=$skip")
    if (dropped > 0) append(" dropped=$dropped")
}

private fun StringBuilder.field(name: String, value: String?) {
    if (value.isNullOrEmpty()) return
    append(" $name=${privacy(DiagLevel.DEBUG, value, keepAddress = true)}")
}

private fun renderRejected(nowMs: Long): String = "rcv=$nowMs event=diag_rejected code=bad_fact"

private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b""")
private val labeledAddress = Regex("""(?i)\b(domain|ip|dest|server|host)=([^\s]+)""")

internal fun privacy(level: DiagLevel, raw: String, keepAddress: Boolean): String {
    val redacted = SecretRedactor.redact(raw)
    if (keepAddress || level != DiagLevel.WARN) return redacted
    return labeledAddress.replace(ipv6.replace(ipv4.replace(redacted, "*"), "*")) { "${it.groupValues[1]}=*" }
}
