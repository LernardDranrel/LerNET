package app.lernet.ui.home

import app.lernet.engine.policy.BudgetNotice
import app.lernet.engine.policy.LinkHold
import app.lernet.engine.policy.LinkJudge
import app.lernet.engine.text.EditorWords
import app.lernet.engine.text.ExpertWords
import app.lernet.engine.text.HomeWords
import app.lernet.engine.text.ImportWords
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
    fun editorWordsMatchWindows() {
        same("cfg_title", EditorWords.TITLE)
        same("cfg_local_section", EditorWords.LOCAL)
        same("cfg_local_help", EditorWords.LOCAL_HELP)
        same("cfg_remote_section", EditorWords.REMOTE)
        same("cfg_remote_help", EditorWords.REMOTE_HELP)
        same("cfg_node", EditorWords.NODE)
        same("cfg_node_why", EditorWords.NODE_WHY)
        same("cfg_server", EditorWords.SERVER)
        same("cfg_port", EditorWords.PORT)
        same("cfg_server_error", EditorWords.SERVER_ERROR)
        same("cfg_port_error", EditorWords.PORT_ERROR)
        same("cfg_disguise", EditorWords.DISGUISE)
        same("cfg_disguise_why", EditorWords.DISGUISE_WHY)
        same("cfg_sni", EditorWords.SNI)
        same("cfg_fingerprint", EditorWords.FINGERPRINT)
        same("cfg_fingerprint_help", EditorWords.FINGERPRINT_HELP)
        same("cfg_path", EditorWords.PATH)
        same("cfg_dns", EditorWords.DNS)
        same("cfg_dns_why", EditorWords.DNS_WHY)
        same("cfg_dns_type", EditorWords.DNS_TYPE)
        same("cfg_dns_type_help", EditorWords.DNS_TYPE_HELP)
        same("cfg_dns_address", EditorWords.DNS_ADDRESS)
        same("cfg_dns_error", EditorWords.DNS_ERROR)
        same("cfg_advanced", EditorWords.ADVANCED)
        same("cfg_local_defaults_help", EditorWords.ADVANCED_HELP)
        same("cfg_mode", EditorWords.MODE)
        same("cfg_mode_help", EditorWords.MODE_HELP)
        same("cfg_system_defined", EditorWords.SYSTEM_DEFINED)
        same("cfg_engine", EditorWords.ENGINE)
        same("cfg_copy_json", EditorWords.COPY_JSON)
        same("cfg_json_secret_hint", EditorWords.JSON_SECRET)
        same("cfg_save", EditorWords.SAVE)
        assertEquals(EditorWords.fingerprintDefault("firefox"), strings.getValue("cfg_fingerprint_default").replace("%1\$s", "firefox"))
    }

    @Test
    fun importWordsMatchWindows() {
        same("import_hint", ImportWords.HINT)
        same("import_title", ImportWords.TITLE)
        same("import_input_example", ImportWords.INPUT_EXAMPLE)
        same("paste_clipboard", ImportWords.PASTE)
        same("import_from_file", ImportWords.FROM_FILE)
        same("import_action", ImportWords.ACTION)
        same("importing", ImportWords.BUSY)
        same("profile_name", ImportWords.NAME)
        same("profile_name_hint", ImportWords.NAME_HINT)
    }

    @Test
    fun expertPultWordsMatchWindows() {
        same("expert_tile_exits", ExpertWords.TILE_EXITS)
        same("expert_tile_connections", ExpertWords.TILE_CONNECTIONS)
        same("expert_tile_protection", ExpertWords.TILE_PROTECTION)
        same("expert_tile_protection_on", ExpertWords.PROTECTION_ON)
        same("expert_tile_protection_check", ExpertWords.PROTECTION_CHECK)
        same("expert_activity_search", ExpertWords.ACTIVITY_SEARCH)
        same("expert_activity_all", ExpertWords.FILTER_ALL)
        same("expert_activity_active", ExpertWords.FILTER_ACTIVE)
        same("expert_activity_long", ExpertWords.FILTER_LONG)
        same("expert_activity_closed", ExpertWords.FILTER_CLOSED)
        same("expert_activity_empty", ExpertWords.ACTIVITY_EMPTY)
        assertEquals("ещё нет", ExpertWords.exitsValue(0, 0))
        assertEquals("2 из 3", ExpertWords.exitsValue(2, 3))
        assertEquals("1 активное", ExpertWords.connectionsValue(1))
        assertEquals("11 активных", ExpertWords.connectionsValue(11))
        same("expert_exits_empty_title", ExpertWords.EXITS_EMPTY)
        same("expert_sleeping_only", ExpertWords.SLEEPING_ONLY)
        same("expert_sleeping_none", ExpertWords.SLEEPING_NONE)
        same("expert_external_add", ExpertWords.EXTERNAL_ADD)
        same("expert_external_edit", ExpertWords.EXTERNAL_EDIT)
        same("expert_dns_row", ExpertWords.DNS_ROW)
        same("expert_dns_row_hint", ExpertWords.DNS_ROW_HINT)
        same("expert_health_row", ExpertWords.HEALTH_ROW)
        same("expert_health_row_hint", ExpertWords.HEALTH_ROW_HINT)
        same("expert_safety_title", ExpertWords.SAFETY_TITLE)
        assertEquals("Скорость ещё не измерена", ExpertWords.eventsRate(null, null))
        assertEquals("Скорость 2 из 3: ↑ 1,5 КБ/с · ↓ 2 МБ/с", ExpertWords.eventsRate(1536, 2L * 1024 * 1024, 2, 3))
        assertEquals(
            "Прямой выход · основной интерфейс wlan0: IPv4 — маршрут есть; IPv6 — пути нет",
            ExpertWords.directFamilies(app.lernet.engine.policy.ExpertDirectNetworkFacts(
                interfaceName = "wlan0",
                ipv4 = app.lernet.engine.policy.DirectFamilyAvailability.AVAILABLE,
                ipv6 = app.lernet.engine.policy.DirectFamilyAvailability.UNAVAILABLE,
                source = "test", networkEpoch = 0, observedAtMs = 0,
            )),
        )
    }

    @Test
    fun pathCheckWordsMatchWindows() {
        val w = app.lernet.engine.text.PathCheckWords
        listOf(
            "expert_simulation_hint" to w.HINT, "expert_simulation_choose_history" to w.CHOOSE,
            "expert_simulation_create_example" to w.CREATE, "expert_simulation_edit_parameters" to w.EDIT,
            "expert_simulation_hide_editor" to w.HIDE_EDITOR, "expert_simulation_history" to w.TAG_HISTORY,
            "expert_simulation_modified" to w.TAG_MODIFIED, "expert_simulation_new" to w.TAG_NEW,
            "expert_simulation_history_first" to w.HISTORY_FIRST, "expert_simulation_recorded_path" to w.RECORDED,
            "expert_simulation_current_path" to w.CURRENT, "expert_simulation_with_edits" to w.WITH_EDITS,
            "expert_simulation_run" to w.RUN, "expert_simulation_domain" to w.DOMAIN,
            "expert_simulation_process" to w.PROCESS, "expert_simulation_port" to w.PORT,
            "expert_simulation_source_port" to w.SOURCE_PORT, "expert_simulation_protocol" to w.PROTOCOL,
            "expert_simulation_unknown" to w.UNKNOWN, "expert_simulation_invalid_example" to w.INVALID_EXAMPLE,
            "expert_simulation_incomplete" to w.INCOMPLETE, "expert_simulation_no_match" to w.NO_MATCH,
            "expert_simulation_invalid" to w.INVALID,
        ).forEach { (key, value) -> same(key, value) }
    }

    @Test
    fun coreFailureTitlesMatchThePhoneCauses() {
        listOf(
            "dial tcp 203.0.113.5:443: connect: connection refused",
            "remote error: tls: handshake failure", "x509: certificate signed by unknown authority",
            "reality handshake failed", "read: connection reset by peer", "unexpected EOF",
            "dial tcp: i/o timeout", "context deadline exceeded", "network is unreachable",
            "start service: something else", "",
        ).forEach { raw ->
            assertEquals(raw, app.lernet.engine.EngineErrorMapper.map(raw).titleRu(), app.lernet.engine.text.CoreFailureWords.title(raw))
        }
    }

    @Test
    fun configListWordsMatchWindows() {
        val w = app.lernet.engine.text.ConfigListWords
        same("drawer_title", w.TITLE)
        assertEquals(strings.getValue("drawer_folder_probe").replace("%1\$d", "1").replace("%2\$d", "2"), w.folderAnswer(1, 2))
        assertEquals(strings.getValue("drawer_folder_checking").replace("%1\$d", "1").replace("%2\$d", "2"), w.folderChecking(1, 2))
        assertEquals("1 конфиг", w.configCount(1))
        assertEquals("2 конфига", w.configCount(2))
        assertEquals("11 конфигов", w.configCount(11))
        assertEquals("Проверено 3 из 4 · отвечают: 2", w.summary(3, 4, 2, checking = false))
    }

    @Test
    fun schemaWordsMatchWindows() {
        val words = app.lernet.engine.text.SchemaWords
        same("route_schema", words.VIEW_SCHEMA)
        same("route_list", words.VIEW_LIST)
        same("expert_align_graph", words.ALIGN)
        same("expert_branching_root", words.BRANCHING)
        same("expert_otherwise_title", words.OTHERWISE)
        same("expert_otherwise_hint", words.OTHERWISE_HINT)
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
