package app.lernet.engine.net.observation

/** How much of a parameter LerNET really reads: ● collected, ◐ the OS gives it but LerNET does not read it yet, ✕ hidden from apps. */
enum class Collected(val mark: String) { YES("●"), NOT_YET("◐"), HIDDEN("✕") }

data class NetworkParam(
    val name: String,
    val value: String,
    val usual: String = "",
    val unusual: Boolean = false,
    val collected: Collected = Collected.YES,
)

data class NetworkParamSection(val title: String, val rows: List<NetworkParam>)

/**
 * "Parameter | Value | Usual" tables for the one-tap network analysis (v2 mockups 08/09).
 * Only values LerNET actually read are judged; free fields (names, addresses, DNS servers) get
 * no verdict. Nothing is invented: what the OS hides or LerNET does not read yet is marked so.
 */
object NetworkTable {
    fun build(snapshot: NetworkSnapshot): List<NetworkParamSection> {
        val physical = snapshot.adapters.filter { !it.virtual }
        val defaultRoutes = snapshot.routes.filter { it.prefix == "0.0.0.0/0" || it.prefix == "::/0" }
        val main = physical.firstOrNull { adapter -> defaultRoutes.any { it.adapterId == adapter.id } } ?: physical.firstOrNull()
        val android = snapshot.platform.equals("Android", ignoreCase = true)
        fun field(sourceId: String, name: String): String? = snapshot.sources.firstOrNull { it.id == sourceId }
            ?.rows?.firstOrNull { it.id == main?.id }?.fields?.get(name)

        val connection = buildList {
            add(NetworkParam("Сеть по умолчанию", main?.let { listOf(it.description, it.name).filter(String::isNotBlank).joinToString(" · ") } ?: "Не видна",
                "Wi-Fi, мобильная сеть или Ethernet", unusual = main == null))
            field("adapters", "Системная проверка интернета")?.let { value ->
                add(NetworkParam("Система подтвердила интернет", value, "Да", unusual = "не подтвердил" in value || "недоступн" in value))
            }
            val others = physical.filter { it.id != main?.id }
            if (others.isNotEmpty()) add(NetworkParam("Другие видимые сети", others.joinToString { listOf(it.description, it.name).filter(String::isNotBlank).joinToString(" · ") }))
        }
        val addresses = buildList {
            val list = main?.addresses.orEmpty()
            val v4 = list.filter { ':' !in it }
            val v6 = list.filter { ':' in it }
            add(NetworkParam("IPv4", v4.joinToString().ifBlank { "Нет" }, "Есть", unusual = main != null && v4.isEmpty()))
            add(NetworkParam("IPv6", v6.joinToString().ifBlank { "Нет" }, "Есть или нет: оба варианта обычны"))
            defaultRoutes.firstOrNull { it.adapterId == main?.id && it.prefix == "0.0.0.0/0" }?.let {
                add(NetworkParam("Шлюз IPv4", it.nextHop, "В своей подсети"))
            }
            main?.mtu?.let { mtu -> add(NetworkParam("MTU", "$mtu байт", "1500", unusual = mtu in 1..1499)) }
                ?: add(NetworkParam("MTU", "Не передан", "1500", collected = Collected.HIDDEN))
        }
        val dns = buildList {
            add(NetworkParam("DNS-серверы", main?.dns?.joinToString()?.ifBlank { null } ?: "Не переданы", "Адрес роутера или провайдера"))
            field("dns", "Частный DNS")?.let { add(NetworkParam("Частный DNS", it, "Автоматически или выключен")) }
        }
        val routes = buildList {
            val mine = defaultRoutes.filter { it.adapterId == main?.id }
            add(NetworkParam("Маршрут по умолчанию", mine.joinToString { "${it.prefix} через ${it.nextHop}" }.ifBlank { "Не виден" },
                "Один маршрут по умолчанию", unusual = main != null && mine.isEmpty()))
            if (android) add(NetworkParam("Полная таблица и метрики", "Android не раскрывает", collected = Collected.HIDDEN))
            else add(NetworkParam("Всего маршрутов в снимке", snapshot.routes.size.toString()))
        }
        val proxy = buildList {
            field("dns", "Прокси")?.let { value ->
                val set = !value.startsWith("Не указан")
                add(NetworkParam("HTTP-прокси сети", value, "Не задан", unusual = set))
            }
        }
        val vpn = buildList {
            val rows = snapshot.sources.firstOrNull { it.id == "vpn" }?.rows.orEmpty()
            val foreign = rows.filter { "LerNET" !in (it.fields["Владелец"] ?: "") }
            val virtual = snapshot.adapters.filter { it.virtual }
            val value = when {
                rows.isNotEmpty() -> rows.joinToString { row -> row.title + (row.fields["Владелец"]?.let { " · $it" } ?: "") }
                virtual.isNotEmpty() -> virtual.joinToString { it.name }
                else -> "Не видно"
            }
            add(NetworkParam("VPN-сети на устройстве", value, "Нет или только LerNET", unusual = foreign.isNotEmpty()))
            if (android) add(NetworkParam("Владелец чужого VPN", "Android не раскрывает", collected = Collected.HIDDEN))
        }
        // Structured «wlan» rows (id wifi / cellular); hidden values say why. On Windows the «wifi» row is
        // parsed from netsh (WindowsWlanText); the raw text stays on the source page.
        val wlan = snapshot.sources.firstOrNull { it.id == "wlan" }
        val radioRows = wlan?.rows?.filter { it.id == "wifi" || it.id == "cellular" }.orEmpty()
        val wifi = radioRows.flatMap { row ->
            row.fields.entries.map { (name, value) ->
                NetworkParam(if (radioRows.size > 1) "${row.title}: $name" else name, value,
                    collected = if (value.startsWith("Скрыто")) Collected.HIDDEN else Collected.YES)
            }
        }.ifEmpty {
            if (android && wlan == null) listOf(NetworkParam("Wi-Fi и мобильная сеть", "Не прочитаны в этом снимке", collected = Collected.NOT_YET))
            else emptyList()
        }
        return listOf(
            NetworkParamSection("Подключение", connection),
            NetworkParamSection("IP-адреса", addresses),
            NetworkParamSection("DNS", dns),
            NetworkParamSection("Маршруты", routes),
            NetworkParamSection("Прокси", proxy),
            NetworkParamSection("Другие VPN", vpn),
            NetworkParamSection(if (android) "Wi-Fi и мобильная сеть" else "Wi-Fi", wifi),
        ).filter { it.rows.isNotEmpty() }
    }

    fun unusualCount(sections: List<NetworkParamSection>): Int = sections.sumOf { section -> section.rows.count { it.unusual } }

    /** Everything as plain text for «Скопировать всё». No passwords or keys are in the snapshot. */
    fun copyText(snapshot: NetworkSnapshot, sections: List<NetworkParamSection>, header: String): String = buildString {
        appendLine(header)
        appendLine("Необычных значений: ${unusualCount(sections)}")
        sections.forEach { section ->
            appendLine()
            appendLine(section.title)
            section.rows.forEach { row ->
                append("${row.collected.mark} ${row.name}: ${row.value}")
                if (row.usual.isNotBlank()) append(" (обычно: ${row.usual})")
                if (row.unusual) append(" — необычно")
                appendLine()
            }
        }
    }.trimEnd()
}
