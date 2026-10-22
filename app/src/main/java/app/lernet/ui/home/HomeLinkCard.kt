package app.lernet.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.model.Group
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.TrafficDisplay
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.status.HopPathLine
import app.lernet.ui.theme.LerNetOk

/** Audit H3 (mockup m1): «Нидерланды · папка «Работа» · автосмена — Правила · Сменить ›». */
@Composable
internal fun ProfileLine(
    name: String?,
    group: Group?,
    empty: Boolean,
    onChange: () -> Unit,
    onRules: (() -> Unit)?,
    proxyMode: Boolean = false,
) {
    Card(
        onClick = onChange,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("home_profile_line"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LerNetLogo(size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: stringResource(if (empty) R.string.empty_profiles else R.string.no_profile),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    empty -> stringResource(R.string.empty_profiles_body)
                    name == null -> stringResource(R.string.choose_profile)
                    // Same order as Windows (HomeWords.profileSubtitle): folder or «без папки», autoswap, mode.
                    else -> listOfNotNull(
                        group?.let { stringResource(R.string.home_profile_folder, it.name) } ?: stringResource(R.string.home_profile_single),
                        stringResource(R.string.home_profile_autoswap).takeIf { group?.autoFailover == true },
                        stringResource(R.string.home_profile_proxy).takeIf { proxyMode },
                    ).joinToString(" · ")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (empty) 4 else 3, overflow = TextOverflow.Ellipsis) // «…· автосмена · режим прокси» was cut to «папка «Работа» · …»
            }
            if (onRules != null) {
                TextButton(onRules, Modifier.heightIn(min = 48.dp).testTag("home_profile_rules")) {
                    Text(stringResource(R.string.home_profile_rules))
                }
            }
            TextButton(onChange, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.home_change_profile)) }
        }
    }
}

/**
 * Audit H1/H6/X5 (mockup m1): everything about the link in one list card — internet through VPN, server,
 * speed, path to the server. The path opens a bottom sheet with the hop strip instead of a big card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit, hopSnapshot: ConnectionSnapshot, onRefreshHop: () -> Unit) {
    val profileId = state.activeProfile?.id
    val probe = profileId?.let(state.probes::get)
    val snapshot = state.snapshot
    val connected = snapshot.state == ConnectionState.CONNECTED
    val running = probe?.running == true
    val liveServerMs = snapshot.serverTcpMs.takeIf { profileId != null && snapshot.activeProfileId == profileId }
    val serverMs = if (probe != null && !probe.running) probe.tcpMs else liveServerMs
    val tunnelMs = probe?.tunnelMs.takeIf { connected && snapshot.activeProfileId == profileId }
    var pathSheet by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth().testTag("home_link_card"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp,
    ) { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        var fullConfirm by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_link_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { profileId?.let { onIntent(HomeIntent.ProbeProfiles(listOf(it))) } },
                enabled = profileId != null && !running,
                modifier = Modifier.heightIn(min = 48.dp).testTag("home_link_check"),
            ) { Text(if (running) stringResource(R.string.home_latency_checking) else app.lernet.engine.policy.LinkCheck.GENTLE_LABEL) }
            TextButton(
                onClick = { fullConfirm = true },
                enabled = profileId != null && !running && !connected,
                modifier = Modifier.heightIn(min = 48.dp).testTag("home_link_check_full"),
            ) { Text(app.lernet.engine.policy.LinkCheck.FULL_LABEL) }
        }
        if (fullConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { fullConfirm = false },
                title = { Text(app.lernet.engine.policy.LinkCheck.FULL_CONFIRM_TITLE) },
                text = { Text(app.lernet.engine.policy.LinkCheck.FULL_CONFIRM_BODY) },
                confirmButton = {
                    TextButton(onClick = {
                        fullConfirm = false
                        profileId?.let { onIntent(HomeIntent.ProbeProfilesFull(listOf(it))) }
                    }) { Text(app.lernet.engine.policy.LinkCheck.FULL_CONFIRM) }
                },
                dismissButton = { TextButton(onClick = { fullConfirm = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        // Resilience: when the check budget is spent, say when the next check is allowed.
        probe?.budgetWaitMs?.let { wait ->
            Text(
                stringResource(R.string.probe_budget_wait, app.lernet.engine.policy.BudgetNotice.seconds(wait)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("home_budget_wait"),
            )
        }
        LinkRow(
            stringResource(R.string.home_link_internet),
            when {
                !connected -> stringResource(R.string.home_link_when_connected)
                running -> stringResource(R.string.home_link_checking)
                tunnelMs != null -> stringResource(R.string.home_link_works, tunnelMs)
                probe?.tunnelChecked == true -> stringResource(R.string.home_link_no_answer)
                else -> stringResource(R.string.home_link_unchecked)
            },
            good = tunnelMs != null && !running, bad = connected && probe?.tunnelChecked == true && tunnelMs == null && !running,
        )
        HorizontalDivider()
        LinkRow(
            stringResource(R.string.home_link_server),
            when {
                running -> stringResource(R.string.home_link_checking)
                serverMs != null -> stringResource(R.string.home_link_answers, serverMs)
                probe != null && !probe.reachable -> stringResource(R.string.home_link_no_answer)
                else -> stringResource(R.string.home_link_unchecked)
            },
            good = serverMs != null && !running, bad = probe != null && !probe.running && !probe.reachable,
        )
        if (connected) {
            val traffic = TrafficDisplay.format(snapshot.uplinkBps, snapshot.downlinkBps, snapshot.uplinkTotal, snapshot.downlinkTotal, snapshot.dnsOk)
            HorizontalDivider()
            LinkRow(stringResource(R.string.home_link_speed), stringResource(R.string.traffic_rates, traffic.uplinkRate, traffic.downlinkRate))
            if (traffic.showTotals) {
                HorizontalDivider()
                LinkRow(stringResource(R.string.home_link_total), stringResource(R.string.traffic_rates, traffic.uplinkTotal, traffic.downlinkTotal))
            }
        }
        HorizontalDivider()
        val hops = hopSnapshot.hops
        val country = hops.lastOrNull { !it.country.isNullOrBlank() }?.country?.uppercase()
        LinkRow(
            stringResource(R.string.home_link_path),
            when {
                hopSnapshot.hopRunning -> stringResource(R.string.home_link_checking)
                hops.isEmpty() -> stringResource(R.string.home_link_path_check)
                else -> stringResource(R.string.home_link_path_value,
                    listOfNotNull(pluralStringResource(R.plurals.hops_count, hops.size, hops.size), country).joinToString(" · "))
            },
            tag = "home_link_path",
            onClick = if (profileId != null) {
                {
                    if (hops.isEmpty() && !hopSnapshot.hopRunning) onRefreshHop()
                    pathSheet = true
                }
            } else null,
        )
    } }
    if (pathSheet) {
        ModalBottomSheet(onDismissRequest = { pathSheet = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                HopPathLine(snapshot = hopSnapshot, onRefresh = onRefreshHop, enabled = profileId != null)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, good: Boolean = false, bad: Boolean = false, tag: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
            color = when {
                good -> LerNetOk
                bad -> MaterialTheme.colorScheme.error
                onClick != null -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Audit H4: the request journal one tap from home, under «Связь» (same as Windows). */
@Composable
internal fun JournalRow(onOpen: () -> Unit) {
    OutlinedButton(onOpen, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home_journal_row")) {
        Text(stringResource(R.string.home_journal_row))
    }
}

/**
 * Audit H7: one «attention» zone. The most important hint is shown, the rest fold behind «Ещё N»,
 * so the button and «Связь» do not jump when hints come and go. Items arrive already in priority order.
 */
@Composable
internal fun AttentionZone(items: List<@Composable ColumnScope.() -> Unit>) {
    if (items.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("home_attention"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (if (all) items else items.take(1)).forEach { it() }
        if (items.size > 1) {
            TextButton({ all = !all }, Modifier.heightIn(min = 48.dp).testTag("home_attention_more")) {
                Text(if (all) stringResource(R.string.home_attention_less) else stringResource(R.string.home_attention_more, items.size - 1))
            }
        }
    }
}
