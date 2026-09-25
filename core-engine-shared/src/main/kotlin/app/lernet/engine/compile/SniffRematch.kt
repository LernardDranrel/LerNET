package app.lernet.engine.compile

object SniffRematch {
    private val tcpDial =
        Regex("""outbound/[^/\[]+\[([^\]]+)\]: outbound connection to (\S+)""")

    fun note(finalTag: String): String = "sniff rematch TCP → outbound=$finalTag"

    fun target(
        protocol: String?,
        port: Int?,
        privateIp: Boolean,
        finalTag: String,
    ): String =
        when {
            protocol == "dns" || port == 53 -> "hijack-dns"
            privateIp -> "direct"
            else -> finalTag
        }

    fun fromLibboxLine(line: String): String? {
        if (line.contains("outbound packet connection")) return null
        val match = tcpDial.find(line) ?: return null
        return "sniff rematch TCP outbound=${match.groupValues[1]} dest=${match.groupValues[2]}"
    }
}
