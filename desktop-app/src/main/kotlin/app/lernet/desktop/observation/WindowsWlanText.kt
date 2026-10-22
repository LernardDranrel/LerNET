package app.lernet.desktop.observation

import app.lernet.engine.net.observation.EvidenceRow

/**
 * Reads «netsh wlan show interfaces» into the same «wifi» row the Android table uses.
 *
 * netsh prints its labels in the language of Windows, so the parser relies first on what does not change
 * with the language: the «SSID»/«BSSID» labels, «82%», «802.11ax», «5 GHz»/«5 ГГц». Labels that do change
 * (channel, rates, state, authentication) are recognised in English and Russian; in other languages those
 * lines are simply left out of the table and stay on the source page as raw text.
 */
internal object WindowsWlanText {
    const val NAME_HIDDEN =
        "Скрыто. Windows показывает имя сети Wi-Fi только приложениям с доступом к местоположению; LerNET не определяет и не хранит местоположение"

    fun parse(text: String): List<EvidenceRow> {
        val hidden = LOCATION_HINTS.any { text.contains(it, ignoreCase = true) }
        return blocks(text).mapNotNull { pairs -> row(pairs, hidden) }
    }

    private fun blocks(text: String): List<List<Pair<String, String>>> {
        val result = mutableListOf<List<Pair<String, String>>>()
        var current = mutableListOf<Pair<String, String>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val colon = line.indexOf(" : ").takeIf { it > 0 } ?: line.indexOf(':').takeIf { it > 0 && line.startsWith(" ") }
            if (colon == null) {
                if (line.isBlank() && current.isNotEmpty()) {
                    result += current
                    current = mutableListOf()
                }
                return@forEach
            }
            val label = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trimStart(':', ' ').trim()
            // A new «Name/Имя» line inside a block starts the next adapter (netsh does not always print a blank line).
            if (isName(label) && current.any { isName(it.first) }) {
                result += current
                current = mutableListOf()
            }
            current += label to value
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun row(pairs: List<Pair<String, String>>, hidden: Boolean): EvidenceRow? {
        fun find(predicate: (String, String) -> Boolean) = pairs.firstOrNull { (l, v) -> predicate(l, v) }?.second
        val ssid = find { l, _ -> l.equals("SSID", ignoreCase = true) }
        val signal = find { _, v -> SIGNAL.matches(v) }
        val radio = find { _, v -> v.startsWith("802.11") }
        // An adapter that is not connected prints neither a signal nor a network name.
        if (ssid == null && signal == null) return null
        val fields = linkedMapOf<String, String>()
        fields["Имя сети"] = ssid?.takeIf { it.isNotBlank() } ?: if (hidden) NAME_HIDDEN else "Windows не сообщила имя сети"
        signal?.let { fields["Сигнал"] = "$it · ${signalWord(it.trimEnd('%').trim().toIntOrNull() ?: 0)}" }
        find { _, v -> BAND.matches(v) }?.let { fields["Частота"] = it.replace("GHz", "ГГц").replace('.', ',') }
        find { l, v -> l.lowercase() in CHANNEL && v.all(Char::isDigit) }?.let { fields["Канал"] = it }
        radio?.let { fields["Стандарт"] = "$it · ${generation(it)}" }
        find { l, _ -> isRate(l) && RECEIVE.any { it in l.lowercase() } }?.let { fields["Скорость связи с роутером"] = "$it Мбит/с (приём)" }
        find { l, _ -> isRate(l) && TRANSMIT.any { it in l.lowercase() } }?.let { fields["Скорость передачи"] = "$it Мбит/с" }
        find { l, _ -> l.lowercase() in AUTH }?.let { fields["Защита сети"] = it }
        val title = find { l, _ -> isName(l) }?.takeIf { it.isNotBlank() } ?: "Wi-Fi"
        return EvidenceRow("wifi", title, fields)
    }

    private fun isName(label: String) = label.lowercase() in NAME
    private fun isRate(label: String) = label.contains("Mbps", ignoreCase = true) || label.contains("Мбит/с", ignoreCase = true)

    private fun signalWord(percent: Int) = when {
        percent >= 80 -> "отличный"
        percent >= 60 -> "хороший"
        percent >= 40 -> "средний"
        else -> "слабый"
    }

    private fun generation(radio: String) = when (radio.removePrefix("802.11").lowercase()) {
        "be" -> "Wi-Fi 7"
        "ax" -> "Wi-Fi 6"
        "ac" -> "Wi-Fi 5"
        "n" -> "Wi-Fi 4"
        else -> "старый стандарт"
    }

    private val SIGNAL = Regex("""^\d{1,3}\s?%$""")
    private val BAND = Regex("""^\d+([.,]\d+)?\s?(GHz|ГГц)$""", RegexOption.IGNORE_CASE)
    private val NAME = setOf("name", "имя")
    private val CHANNEL = setOf("channel", "канал")
    private val AUTH = setOf("authentication", "проверка подлинности")
    private val RECEIVE = listOf("receive", "прием", "приём", "получ")
    private val TRANSMIT = listOf("transmit", "передач")
    private val LOCATION_HINTS = listOf("location permission", "ms-settings:privacy-location", "расположени", "местоположени")
}
