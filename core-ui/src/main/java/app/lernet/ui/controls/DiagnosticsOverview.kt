package app.lernet.ui.controls

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One Diagnostics page, the same words on Android and Windows and in both modes.
 *
 * Pure text and state: nothing here measures, probes or sends anything. Platforms pass in what they already know
 * (tunnel state, the existing health check, DNS status, results of checks the user started) and get back the verdict,
 * the list of checks and short human texts. External checks (the public IP and the path to the server) are only ever
 * described here as «по нажатию» until the user has run them.
 */
enum class DiagTone { OK, WARN, BAD, IDLE }

enum class DiagLink { OFF, STARTING, ON, FAILED }

/** Where a connection goes. Shown as one short word next to the application. */
enum class DiagRoute { VPN, DIRECT, BLOCKED, UNKNOWN }

data class DiagFacts(
    val expert: Boolean,
    val link: DiagLink,
    /** Profile or exit name; shown only when it is known. */
    val server: String? = null,
    /** Existing health check through the tunnel: true = sites open, false = no answer, null = not known yet. */
    val sitesOk: Boolean? = null,
    /** Existing DNS status. null hides the row: the platform does not report it. */
    val dnsOk: Boolean? = null,
    /** Short reason from the engine when the start failed. */
    val failure: String? = null,
    /** Connections that sent data and never got a reply. */
    val silentConnections: Int = 0,
    /** Events marked as warnings (Expert). */
    val warnings: Int = 0,
    /** Result of the user-started IP check, already formatted. */
    val externalIp: String? = null,
    val externalIpRunning: Boolean = false,
    val externalIpFailed: Boolean = false,
    /** Hops found by the user-started path check. */
    val pathHops: Int? = null,
    val pathRunning: Boolean = false,
)

data class DiagVerdict(val title: String, val advice: String, val tone: DiagTone)

/** [action] is set only for external checks: the button label that opens the confirmation. */
data class DiagCheckRow(
    val key: String,
    val title: String,
    val value: String,
    val tone: DiagTone,
    val action: String? = null,
)

data class DiagLogEntry(val atMs: Long, val text: String, val warning: Boolean = false)

object DiagnosticsOverview {
    const val TITLE = "Диагностика"
    const val CHECKS = "Проверки"
    const val PATH = "Путь до сервера"
    const val CONNECTIONS = "Соединения"
    const val EVENTS = "Последние события"
    const val SHOW_ALL = "Показать все"
    const val SHOW_LESS = "Свернуть"
    const val CANCEL = "Отмена"
    const val NO_EVENTS = "Пока ничего не произошло"
    const val NETWORK_FEATURES = "Особенности сети"
    const val CHECK_IP = "Внешний IP"
    const val CHECK_PATH = "Путь до сервера"
    const val ON_TAP = "по нажатию"
    const val RUN_IP = "Узнать"
    const val RUN_PATH = "Проверить"
    const val RUNNING = "проверяем…"
    const val RECORD_START = "Записывать"
    const val RECORD_STOP = "Остановить запись"
    const val RECORDING = "Идёт запись"
    const val CONNECTION_CHECK = "Проверить отклик"
    const val NO_REPLY = "нет ответа"
    const val NOW = "сейчас"
    const val CLOSED = "закрыто"
    const val TECH_LOG = "Технический журнал"
    const val PATH_CONFIRM_TITLE = "Проверить путь до сервера?"
    const val PATH_CONFIRM_BODY = "LerNET отправит пробные пакеты к серверу и покажет, через какие узлы и страны они проходят. " +
        "Узлы по дороге увидят эти пакеты. Больше ничего не отправляется."
    const val EXTERNAL_NOTE = "Внешние проверки идут через сторонний сервис и запускаются только по нажатию."

    /** One sentence on top of the page and what to do about it. */
    fun verdict(f: DiagFacts): DiagVerdict {
        val name = if (f.expert) "Экспертный режим" else "VPN"
        return when (f.link) {
            DiagLink.OFF -> DiagVerdict(
                "$name выключен",
                if (f.expert) "Поднимите рубильник на главном экране. Данные сети ниже собираются и без него."
                else "Включите его на главном экране. Данные сети ниже собираются и без него.",
                DiagTone.IDLE,
            )
            DiagLink.STARTING -> DiagVerdict("Подключаемся…", "Обычно это занимает несколько секунд.", DiagTone.IDLE)
            DiagLink.FAILED -> DiagVerdict(
                "Не удалось подключиться",
                f.failure?.takeIf { it.isNotBlank() }?.let { "$it. Попробуйте другой профиль." }
                    ?: "Попробуйте другой профиль. Подробности — в событиях ниже.",
                DiagTone.BAD,
            )
            DiagLink.ON -> when {
                f.sitesOk == false -> DiagVerdict(
                    "Сайты через VPN не открываются",
                    "Подключение есть, но запросы через сервер не проходят. Попробуйте другой профиль.",
                    DiagTone.BAD,
                )
                f.dnsOk == false -> DiagVerdict(
                    "Имена сайтов не находятся",
                    "DNS не отвечает. Смените DNS в Настройках или выберите другой профиль.",
                    DiagTone.BAD,
                )
                f.silentConnections > 0 -> DiagVerdict(
                    "Часть соединений без ответа",
                    "${f.silentConnections} ${plural(f.silentConnections, "соединение", "соединения", "соединений")} " +
                        "без ответа. Посмотрите их ниже: если это один сайт, дело, скорее всего, в нём.",
                    DiagTone.WARN,
                )
                f.warnings > 0 -> DiagVerdict(
                    "Работает, есть предупреждения",
                    "Посмотрите последние события ниже.",
                    DiagTone.WARN,
                )
                f.sitesOk == null -> DiagVerdict(
                    "Подключено",
                    "Проверяем, открываются ли сайты. Это происходит само.",
                    DiagTone.OK,
                )
                else -> DiagVerdict(
                    "Всё работает",
                    listOfNotNull(f.server?.let { "Подключено через «$it»." }, "Страница обновляется сама.").joinToString(" "),
                    DiagTone.OK,
                )
            }
        }
    }

    /** Local checks first (they come by themselves), then the two external ones that wait for a tap. */
    fun checks(f: DiagFacts): List<DiagCheckRow> = buildList {
        add(
            DiagCheckRow(
                "link", if (f.expert) "Экспертный режим" else "Подключение",
                when (f.link) {
                    DiagLink.OFF -> "выключено"
                    DiagLink.STARTING -> "подключаемся…"
                    DiagLink.ON -> "работает"
                    DiagLink.FAILED -> "ошибка"
                },
                when (f.link) {
                    DiagLink.OFF -> DiagTone.IDLE
                    DiagLink.STARTING -> DiagTone.IDLE
                    DiagLink.ON -> DiagTone.OK
                    DiagLink.FAILED -> DiagTone.BAD
                },
            ),
        )
        val on = f.link == DiagLink.ON
        add(
            DiagCheckRow(
                "sites", "Сайты через VPN",
                when {
                    !on -> "—"
                    f.sitesOk == true -> "открываются"
                    f.sitesOk == false -> NO_REPLY
                    else -> RUNNING
                },
                when {
                    !on -> DiagTone.IDLE
                    f.sitesOk == true -> DiagTone.OK
                    f.sitesOk == false -> DiagTone.BAD
                    else -> DiagTone.IDLE
                },
            ),
        )
        f.dnsOk?.let { ok ->
            add(
                DiagCheckRow(
                    "dns", "Имена сайтов (DNS)",
                    if (!on) "—" else if (ok) "отвечают" else "не отвечают",
                    if (!on) DiagTone.IDLE else if (ok) DiagTone.OK else DiagTone.BAD,
                ),
            )
        }
        if (on) {
            add(
                DiagCheckRow(
                    "silent", "Соединения без ответа",
                    if (f.silentConnections == 0) "нет" else f.silentConnections.toString(),
                    if (f.silentConnections == 0) DiagTone.OK else DiagTone.WARN,
                ),
            )
        }
        add(
            DiagCheckRow(
                "ip", CHECK_IP,
                when {
                    f.externalIpRunning -> RUNNING
                    f.externalIp != null -> f.externalIp
                    f.externalIpFailed -> "не удалось узнать"
                    else -> ON_TAP
                },
                when {
                    f.externalIp != null -> DiagTone.OK
                    f.externalIpFailed -> DiagTone.WARN
                    else -> DiagTone.IDLE
                },
                action = RUN_IP.takeUnless { f.externalIpRunning },
            ),
        )
        add(
            DiagCheckRow(
                "path", CHECK_PATH,
                when {
                    f.pathRunning -> RUNNING
                    f.pathHops != null && f.pathHops > 0 -> "${f.pathHops} ${plural(f.pathHops, "узел", "узла", "узлов")}"
                    else -> ON_TAP
                },
                if (f.pathHops != null && f.pathHops > 0) DiagTone.OK else DiagTone.IDLE,
                action = RUN_PATH.takeUnless { f.pathRunning },
            ),
        )
    }

    /** «4 из 5»: local checks that passed out of the local checks that apply now. */
    fun checksSummary(rows: List<DiagCheckRow>): String {
        val local = rows.filter { it.key != "ip" && it.key != "path" && it.value != "—" }
        if (local.all { it.tone == DiagTone.IDLE }) return ""
        return "${local.count { it.tone == DiagTone.OK }} из ${local.size}"
    }

    fun connectionsEmpty(link: DiagLink, expert: Boolean): String = when (link) {
        DiagLink.ON -> "Соединений пока нет"
        DiagLink.STARTING -> "Подключаемся — соединения появятся здесь"
        else -> if (expert) "Поднимите рубильник — здесь появятся соединения" else "Включите VPN — здесь появятся соединения"
    }

    fun route(route: DiagRoute): String = when (route) {
        DiagRoute.VPN -> "через VPN"
        DiagRoute.DIRECT -> "напрямую"
        DiagRoute.BLOCKED -> "заблокировано"
        DiagRoute.UNKNOWN -> ""
    }

    /** «149.154.167.51:443» → «149.154.167.51»; «[2001:db8::1]:443» → «2001:db8::1». Hosts without a port stay as they are. */
    fun hostOnly(destination: String): String {
        val trimmed = destination.trim()
        if (trimmed.startsWith("[")) return trimmed.substringAfter('[').substringBefore(']')
        return if (trimmed.count { it == ':' } == 1) trimmed.substringBefore(':') else trimmed
    }

    /** Subtitle of a connection row: «Chrome · через VPN». */
    fun connectionSubtitle(app: String, route: DiagRoute): String =
        listOf(app, route(route)).filter(String::isNotBlank).joinToString(" · ")

    /** An Android UID without a visible package. Never replaced with a guessed name. */
    fun systemApp(uid: Int): String = "Система (uid $uid)"

    const val UNKNOWN_APP = "Приложение не определено"
    const val SYSTEM = "Система"
    const val SIMULATE = "Куда ведут правила"

    /** «Особенности сети» from the direct-exit facts the Expert engine already reports. Title to short note. */
    fun networkFeatures(ipv6Missing: Boolean, ipv4Limited: Boolean): List<Pair<String, String>> = buildList {
        if (ipv4Limited) add("Прямой выход ограничен" to "сайты напрямую могут открываться медленнее")
        if (ipv6Missing) add("IPv6 нет в этой сети" to "ничего делать не нужно")
    }

    /** Connection detail labels, the same on both platforms. */
    const val D_ADDRESS = "Адрес"
    const val D_PROTOCOL = "Протокол"
    const val D_DECISION = "Решение"
    const val D_RULE = "Правило"
    const val D_CHAIN = "Цепочка"
    const val D_TRAFFIC = "Передано"
    const val D_PROCESS = "Программа"
    const val D_ERROR = "Ошибка"

    fun route(exitKnown: Boolean, decision: String): DiagRoute {
        val text = decision.lowercase()
        return when {
            "заблок" in text || "block" in text || "reject" in text -> DiagRoute.BLOCKED
            "напрям" in text || "direct" in text -> DiagRoute.DIRECT
            exitKnown -> DiagRoute.VPN
            else -> DiagRoute.UNKNOWN
        }
    }

    fun showAll(total: Int): String = "$SHOW_ALL ($total)"

    fun time(atMs: Long): String = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(atMs))

    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}

/**
 * Human events of the basic mode, derived from state changes the app already observes. Nothing is polled or measured
 * for it. Keeps the newest [limit] entries, newest last.
 */
class DiagEventLog(private val limit: Int = 50) {
    private var link: DiagLink? = null
    private var sites: Boolean? = null
    var entries: List<DiagLogEntry> = emptyList()
        private set

    /** Returns true when a new event was added. */
    fun observe(nowMs: Long, link: DiagLink, server: String?, sitesOk: Boolean?, failure: String?): Boolean {
        val before = entries.size
        val previous = this.link
        if (previous != link) {
            when (link) {
                DiagLink.ON -> add(nowMs, listOfNotNull("Подключено", server?.let { "«$it»" }).joinToString(" · "))
                DiagLink.OFF -> if (previous != null) add(nowMs, "Выключено")
                DiagLink.STARTING -> add(nowMs, if (previous == DiagLink.ON) "Переподключаемся…" else "Подключаемся…")
                DiagLink.FAILED -> add(nowMs, listOfNotNull("Не удалось подключиться", failure?.takeIf { it.isNotBlank() })
                    .joinToString(": "), warning = true)
            }
            this.link = link
            if (link != DiagLink.ON) sites = null
        }
        if (link == DiagLink.ON && sitesOk != null && sitesOk != sites) {
            if (sitesOk == false) add(nowMs, "Сайты через VPN не отвечают", warning = true)
            else if (sites == false) add(nowMs, "Сайты снова открываются")
            sites = sitesOk
        }
        return entries.size != before
    }

    private fun add(atMs: Long, text: String, warning: Boolean = false) {
        entries = (entries + DiagLogEntry(atMs, text, warning)).takeLast(limit)
    }
}
