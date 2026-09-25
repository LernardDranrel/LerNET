package app.lernet.config.redact

object SecretRedactor {
    private val uuid =
        Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    private val quotedSecrets =
        Regex(
            """"(uuid|password|private_key|private-key|pbk|public_key|public-key|sid|short_id|token|auth)"\s*:\s*"[^"]*"""",
            RegexOption.IGNORE_CASE,
        )
    private val vlessUri = Regex("""vless://[^\s]+""", RegexOption.IGNORE_CASE)
    private val labeledSecrets =
        Regex("""(?i)(?<![\"])\b(pbk|sid|short_id)\s*[:=]\s*([^\s\"',}]+)""")

    fun redact(text: String): String {
        var out = quotedSecrets.replace(text) { match ->
            val key = match.value.substringBefore(":").trim().trim('"')
            """"$key":"***""""
        }
        out = labeledSecrets.replace(out) { match -> "${match.groupValues[1]}:***" }
        out = vlessUri.replace(out, "vless://***")
        out = uuid.replace(out, "***")
        return out
    }
}
