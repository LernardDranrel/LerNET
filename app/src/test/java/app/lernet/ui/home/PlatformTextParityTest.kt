package app.lernet.ui.home

import app.lernet.engine.policy.BudgetNotice
import app.lernet.engine.policy.LinkHold
import app.lernet.engine.policy.LinkJudge
import app.lernet.engine.text.HomeWords
import app.lernet.engine.text.JournalWords
import app.lernet.engine.text.SettingsWords
import app.lernet.engine.text.StatusWords
import app.lernet.engine.text.TrafficUnits
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android keeps these words in resources, Windows in shared code; they must stay the same. */
class PlatformTextParityTest {
    private val strings: Map<String, String> by lazy {
        val dir = listOf("src/main/res/values", "app/src/main/res/values").map(::File).first { it.isDirectory }
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }.toList()
        }.toMap()
    }

    private fun same(resource: String, shared: String) =
        assertEquals("R.string.$resource differs from the Windows text", shared, strings.getValue(resource))

    /** Every word of the plain home: the config line, «Связь», the hold report, the error card. */
    @Test
    fun homeWordsMatchWindows() {
        same("home_tap_connect", HomeWords.TAP_CONNECT)
        same("home_tap_disconnect", HomeWords.TAP_DISCONNECT)
        same("home_profile_rules", HomeWords.RULES)
        same("home_change_profile", HomeWords.CHANGE)
        same("home_profile_single", HomeWords.NO_FOLDER)
        same("home_profile_autoswap", HomeWords.AUTOSWAP)
        same("home_profile_proxy", HomeWords.PROXY_MODE)
        assertEquals(HomeWords.folder("Работа"), strings.getValue("home_profile_folder").replace("%1\$s", "Работа"))
        same("home_link_title", HomeWords.LINK_TITLE)
        same("home_link_check", HomeWords.CHECK)
        same("home_latency_checking", HomeWords.CHECKING_BUTTON)
        same("home_link_internet", HomeWords.INTERNET)
        same("home_link_server", HomeWords.SERVER)
        same("home_link_speed", HomeWords.SPEED)
        same("home_link_total", HomeWords.TOTAL)
        same("home_link_path", HomeWords.PATH)
        same("home_link_checking", HomeWords.CHECKING)
        same("home_link_unchecked", HomeWords.UNCHECKED)
        same("home_link_no_answer", HomeWords.NO_ANSWER)
        same("home_link_when_connected", HomeWords.WHEN_CONNECTED)
        same("home_link_path_check", HomeWords.PATH_CHECK)
        assertEquals(HomeWords.works(142), strings.getValue("home_link_works").replace("%1\$d", "142"))
        assertEquals(HomeWords.answers(38), strings.getValue("home_link_answers").replace("%1\$d", "38"))
        assertEquals(HomeWords.pathValue("7 узлов", "NL"), strings.getValue("home_link_path_value").replace("%1\$s", "7 узлов · NL"))
        assertEquals(HomeWords.pair("1 КБ/с", "2 КБ/с"), strings.getValue("traffic_rates").replace("%1\$s", "1 КБ/с").replace("%2\$s", "2 КБ/с"))
        same("home_journal_row", HomeWords.JOURNAL_ROW)
        same("home_attention_less", HomeWords.ATTENTION_LESS)
        assertEquals(HomeWords.attentionMore(2), strings.getValue("home_attention_more").replace("%1\$d", "2"))
        same("state_holding_no_config", HomeWords.HOLDING)
        same("state_failed", HomeWords.FAILED)
        same("failover_other_folder", HomeWords.OTHER_FOLDER)
        same("failover_turn_off", HomeWords.TURN_OFF)
        same("failover_turn_off_note", HomeWords.TURN_OFF_NOTE)
        same("retry", HomeWords.RETRY)
        same("to_profiles", HomeWords.OTHER_CONFIG)
        same("details", HomeWords.DETAILS)
        same("hide_details", HomeWords.HIDE_DETAILS)
        same("home_proxy_mode_note", HomeWords.PROXY_NOTE)
    }

    /** «Настройки», «Для опытных» and «Приложения через VPN»: the same rows say the same thing. */
    @Test
    fun settingsWordsMatchWindows() {
        same("settings_title", SettingsWords.TITLE)
        same("settings_group_connection", SettingsWords.GROUP_CONNECTION)
        same("settings_group_leaks", SettingsWords.GROUP_LEAKS)
        same("settings_group_help", SettingsWords.GROUP_HELP)
        same("settings_group_app", SettingsWords.GROUP_APP)
        same("settings_mode_row", SettingsWords.MODE)
        same("mode_vpn", SettingsWords.MODE_VPN)
        same("mode_proxy", SettingsWords.MODE_PROXY)
        same("settings_mode_profile_note", SettingsWords.MODE_NOTE)
        same("settings_mode_next_connection", SettingsWords.MODE_NEXT)
        same("settings_proxy_not_running", SettingsWords.PROXY_NOT_RUNNING)
        same("settings_proxy_access", SettingsWords.PROXY_ACCESS)
        same("settings_proxy_password_help", SettingsWords.PROXY_HELP)
        same("proxy_login", SettingsWords.LOGIN)
        same("proxy_password", SettingsWords.PASSWORD)
        same("settings_proxy_password_rules", SettingsWords.PROXY_RULES)
        same("settings_apps_row", SettingsWords.APPS)
        same("apps_title", SettingsWords.APPS)
        same("settings_apps_all", SettingsWords.APPS_ALL)
        same("apps_help", SettingsWords.APPS_HELP)
        same("apps_reconnect", SettingsWords.APPS_RECONNECT)
        same("apps_search", SettingsWords.APPS_SEARCH)
        same("apps_all_on", SettingsWords.APPS_ALL_ON)
        same("settings_boot_row", SettingsWords.BOOT)
        same("settings_boot_hint", SettingsWords.BOOT_HINT)
        same("settings_dns_row", SettingsWords.DNS)
        same("settings_dns_tunnel_short", SettingsWords.DNS_TUNNEL_SHORT)
        same("settings_dns_system_short", SettingsWords.DNS_DIRECT_SHORT)
        same("settings_dns_profile_short", SettingsWords.DNS_PROFILE_SHORT)
        same("cfg_dns_tunnel", SettingsWords.DNS_TUNNEL)
        same("cfg_keep_system", SettingsWords.DNS_DIRECT)
        same("cfg_use_profile", SettingsWords.DNS_PROFILE)
        same("settings_dns_default_help", SettingsWords.DNS_DEFAULT_HELP)
        same("settings_network_row", SettingsWords.NETWORK)
        same("settings_updates_row", SettingsWords.UPDATES)
        same("settings_updates_now", SettingsWords.UPDATES_NOW)
        same("settings_experts_row", SettingsWords.EXPERTS)
        same("settings_experts_title", SettingsWords.EXPERTS)
        same("settings_version_row", SettingsWords.VERSION)
        same("settings_core_row", SettingsWords.CORE)
        same("settings_copy", SettingsWords.COPY)
        same("settings_copied", SettingsWords.COPIED)
        same("settings_defaults_scope", SettingsWords.EXPERTS_SCOPE)
        same("settings_auto_apply", SettingsWords.AUTO_APPLY)
        same("settings_dns_section", SettingsWords.DNS_SECTION)
        same("settings_dns_explained", SettingsWords.DNS_EXPLAINED)
        same("settings_direct_dns", SettingsWords.DIRECT_DNS)
        same("settings_direct_dns_help", SettingsWords.DIRECT_DNS_HELP)
        same("settings_dns_multiple_help", SettingsWords.DNS_MULTIPLE_HELP)
        same("settings_xmux_default", SettingsWords.XHTTP_TITLE)
        same("settings_xmux_explained", SettingsWords.XHTTP_EXPLAINED)
        same("settings_xmux_single", SettingsWords.XHTTP_SINGLE)
        same("settings_xmux_tuning", SettingsWords.XHTTP_TUNING)
        same("settings_xmux_default_help", SettingsWords.XHTTP_DEFAULT_HELP)
        same("settings_mtu_title", SettingsWords.MTU_TITLE)
        same("settings_mtu_help", SettingsWords.MTU_HELP)
        same("cfg_tun_mtu", SettingsWords.MTU)
        same("settings_bytes", SettingsWords.BYTES)
        same("settings_mtu_range", SettingsWords.MTU_RANGE)
        same("settings_mtu_tuning", SettingsWords.MTU_TUNING)
        same("settings_tls_title", SettingsWords.TLS_TITLE)
        same("settings_tls_help", SettingsWords.TLS_HELP)
        same("settings_tls_fragment", SettingsWords.TLS_FRAGMENT)
        same("settings_tls_fragment_help", SettingsWords.TLS_FRAGMENT_HELP)
        same("settings_reality_key_share", SettingsWords.KEY_SHARE)
        same("settings_key_share_auto", SettingsWords.KEY_SHARE_AUTO)
        same("settings_key_share_hybrid", SettingsWords.KEY_SHARE_HYBRID)
        same("settings_key_share_classical", SettingsWords.KEY_SHARE_CLASSICAL)
        same("settings_key_share_help", SettingsWords.KEY_SHARE_HELP)
        same("settings_journal", SettingsWords.JOURNAL)
        same("settings_journal_help", SettingsWords.JOURNAL_HELP)
        same("settings_journal_limit", SettingsWords.JOURNAL_LIMIT)
        same("settings_megabytes", SettingsWords.MEGABYTES)
        same("apply", SettingsWords.APPLY)
        same("settings_journal_confirm_title", SettingsWords.JOURNAL_CONFIRM_TITLE)
        same("log_level", SettingsWords.LOG_LEVEL)
        same("log_warn", SettingsWords.LOG_WARN)
        same("log_info", SettingsWords.LOG_INFO)
        same("log_debug", SettingsWords.LOG_DEBUG)
        same("log_warn_help", SettingsWords.LOG_WARN_HELP)
        same("log_info_help", SettingsWords.LOG_INFO_HELP)
        same("log_debug_help", SettingsWords.LOG_DEBUG_HELP)
        same("log_redact_hint", SettingsWords.LOG_REDACT)
        assertEquals(SettingsWords.appsSome(3), strings.getValue("settings_apps_some").replace("%1\$d", "3"))
        assertEquals(SettingsWords.journalConfirm(20), strings.getValue("settings_journal_confirm_body").replace("%1\$d", "20"))
    }

    @Test
    fun journalWordsMatchWindows() {
        same("diag_title", JournalWords.TITLE)
        same("journal_all", JournalWords.ALL)
        same("journal_vpn", JournalWords.VPN)
        same("journal_direct", JournalWords.DIRECT)
        same("journal_silent", JournalWords.SILENT)
        same("journal_search", JournalWords.SEARCH)
        same("journal_no_reply", JournalWords.NO_REPLY)
        same("journal_badge_vpn", JournalWords.BADGE_VPN)
        same("journal_badge_direct", JournalWords.BADGE_DIRECT)
        same("journal_badge_block", JournalWords.BADGE_BLOCK)
        same("journal_where", JournalWords.WHERE)
        same("journal_site", JournalWords.SITE)
        same("journal_how", JournalWords.HOW)
        same("journal_via_direct", JournalWords.VIA_DIRECT)
        same("journal_via_vpn", JournalWords.VIA_VPN)
        same("journal_rule_title", JournalWords.RULE)
        same("diag_rule_unavailable", JournalWords.RULE_UNKNOWN)
        same("journal_state", JournalWords.STATE)
        same("diag_status_open", JournalWords.STATE_OPEN)
        same("diag_status_ok", JournalWords.STATE_DONE)
        same("diag_status_silent", JournalWords.STATE_SILENT)
        same("journal_bytes", JournalWords.BYTES)
        same("journal_kind", JournalWords.KIND)
        same("journal_chain", JournalWords.CHAIN)
        same("diag_payload_encrypted", JournalWords.ENCRYPTED)
        same("diag_empty", JournalWords.EMPTY)
        same("diag_need_vpn", JournalWords.NEED_VPN)
        same("journal_nothing_found", JournalWords.NOTHING_FOUND)
        same("diag_system", JournalWords.SYSTEM)
        assertEquals(JournalWords.rule("YouTube"), strings.getValue("journal_rule").replace("%1\$s", "YouTube"))
    }

    @Test
    fun statusStripMatchesWindows() {
        same("status_connected", StatusWords.CONNECTED)
        assertEquals(StatusWords.connected("Нидерланды", dnsOk = false),
            strings.getValue("status_connected_profile").replace("%1\$s", "Нидерланды"))
        same("status_connecting", StatusWords.CONNECTING)
        same("status_reconnecting", StatusWords.RECONNECTING)
        same("status_off", StatusWords.OFF)
        same("status_failed", StatusWords.FAILED)
        same("status_no_link_held", StatusWords.NO_LINK_HELD)
        same("status_hold", StatusWords.HOLD)
        same("status_jammed", StatusWords.JAMMED)
        same("status_no_reply", StatusWords.NO_REPLY)
    }

    @Test
    fun trafficUnitsAreTheSame() {
        assertEquals("0 Б", TrafficUnits.bytes(0))
        assertEquals("1,5 КБ", TrafficUnits.bytes(1536))
        assertEquals("2 МБ", TrafficUnits.bytes(2L * 1024 * 1024))
        assertEquals("1,2 ГБ", TrafficUnits.bytes((1.2 * (1L shl 30)).toLong()))
        assertEquals("1,5 КБ/с", app.lernet.engine.TrafficDisplay.formatRate(1536))
    }

    @Test
    fun budgetWaitMatchesWindows() {
        assertEquals(BudgetNotice.short(7_000L), strings.getValue("probe_budget_wait_short").replace("%1\$d", "7"))
        assertEquals(BudgetNotice.full(7_000L, expert = false), strings.getValue("probe_budget_wait").replace("%1\$d", "7"))
    }

    @Test
    fun holdStatusMatchesWindows() {
        assertEquals(LinkJudge.holdMessage(LinkHold.JAMMED), strings.getValue("state_hold_jammed"))
        assertEquals(LinkJudge.holdMessage(LinkHold.ALL_SILENT), strings.getValue("state_hold_all_silent"))
        assertEquals(
            LinkJudge.holdMessage(LinkHold.ALL_SILENT, "2 мин"),
            strings.getValue("state_hold_all_silent_next").replace("%1\$s", "2\u00A0мин"),
        )
    }
}
