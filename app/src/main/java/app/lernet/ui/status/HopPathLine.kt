package app.lernet.ui.status

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.net.HopCard
import app.lernet.engine.net.HopChip
import app.lernet.engine.net.HopPath
import app.lernet.engine.net.HopSilence
import app.lernet.ui.motion.rememberReduceMotion
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton

/** Compact full-width hop strip; tap opens Windows-tracert-class hop cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HopPathLine(
    snapshot: ConnectionSnapshot,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val channelUp = snapshot.state == ConnectionState.CONNECTED && !ChannelWatch.isHonestlyUnhealthy(snapshot.channel)
    val probing = snapshot.hopRunning
    val chips = HopPath.chips(snapshot.hops, awaiting = probing)
    val note = if (!probing && snapshot.hops.isNotEmpty()) {
        HopPath.silence(snapshot.hops, channelUp)
    } else {
        null
    }
    val stripCd = stringResource(R.string.hop_strip_cd)
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.home_hop_title), style = MaterialTheme.typography.titleSmall)
                    if (snapshot.hopHost.isNotBlank()) {
                        Text(
                            snapshot.hopHost, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = onRefresh, enabled = enabled && !probing, modifier = Modifier.lernetButton()) {
                    Text(stringResource(if (probing) R.string.home_hop_checking else R.string.home_hop_check))
                }
            }
            HopStrip(
                chips = chips,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = stripCd }
                    .clickable(enabled = enabled && !probing) {
                        if (snapshot.hops.isNotEmpty()) sheetOpen = true else onRefresh()
                    },
            )
            if (snapshot.hops.isEmpty() && !probing) {
                Text(
                    stringResource(R.string.home_hop_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (note) {
                HopSilence.PARTIAL -> Text(
                    stringResource(R.string.hop_channel_ok_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HopSilence.ALL -> Text(
                    stringResource(R.string.hop_channel_ok_silent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Unit
            }
            if (chips.any { it == HopChip.Opaque || it == HopChip.Timeout }) {
                Text(
                    stringResource(R.string.hop_geo_unknown_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot.hops.any { it.synthetic && !it.timedOut }) {
                Text(
                    stringResource(R.string.hop_tcp_path_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot.hops.isNotEmpty() && !probing) {
                TextButton(onClick = { sheetOpen = true }, modifier = Modifier.lernetButton()) {
                    Text(stringResource(R.string.home_hop_details))
                }
            }
        }
    }
    if (sheetOpen && snapshot.hops.isNotEmpty()) {
        HopCardsSheet(
            cards = HopPath.cards(snapshot.hops),
            target = snapshot.hopHost,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun HopStrip(chips: List<HopChip>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val steps = chips.filterNot { it == HopChip.Phone || it == HopChip.TcpDestination }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Outlined.Smartphone,
                    contentDescription = stringResource(R.string.hop_phone_chip),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(14.dp).size(24.dp),
                )
            }
            HopConnector()
            if (steps.isEmpty()) {
                Text(
                    stringResource(R.string.hop_awaiting_chip),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            steps.forEachIndexed { index, chip ->
                if (index > 0) HopConnector()
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        Modifier.widthIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        HopChipMark(chip)
                        Text(
                            if (chip is HopChip.Country) chip.code else (index + 1).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HopConnector()
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    stringResource(R.string.home_hop_server),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 17.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (scroll.maxValue > 0) {
            Text(
                stringResource(R.string.home_hop_scroll_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HopConnector() {
    Icon(
        Icons.AutoMirrored.Outlined.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(12.dp),
    )
}

@Composable
private fun HopChipMark(chip: HopChip) {
    when (chip) {
        HopChip.Phone -> Icon(
            imageVector = Icons.Outlined.Smartphone,
            contentDescription = stringResource(R.string.hop_phone_chip),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        is HopChip.Country -> Text(
            HopPath.flagEmoji(chip.code) ?: chip.code,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        HopChip.Timeout -> Text(
            stringResource(R.string.hop_timeout_chip),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HopChip.Opaque -> Text(
            stringResource(R.string.hop_opaque_chip),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HopChip.TcpDestination -> Text(
            stringResource(R.string.hop_tcp_chip),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        HopChip.Awaiting -> AwaitingPulse()
    }
}

@Composable
private fun AwaitingPulse() {
    val reduceMotion = rememberReduceMotion()
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "hopAwait")
        val animated by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hopAwaitAlpha",
        )
        animated
    }
    Text(
        stringResource(R.string.hop_awaiting_chip),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HopCardsSheet(cards: List<HopCard>, target: String, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.hop_sheet_title), style = MaterialTheme.typography.titleLarge)
            if (target.isNotBlank()) {
                Text(
                    stringResource(R.string.hop_target, target),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            cards.forEach { card -> HopCardRow(card) }
            Text(
                stringResource(R.string.hop_details_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().lernetButton()) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun HopCardRow(card: HopCard) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.hop_card_index, card.index),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                card.timedOut -> Text(
                    stringResource(R.string.hop_timeout),
                    style = MaterialTheme.typography.titleMedium,
                )
                card.synthetic -> Text(
                    stringResource(R.string.hop_card_destination),
                    style = MaterialTheme.typography.titleMedium,
                )
                else -> {
                    val flag = card.country?.let(HopPath::flagEmoji)
                    val title = card.title
                    Text(
                        listOfNotNull(flag, title).joinToString(" ").ifBlank {
                            card.address ?: stringResource(R.string.hop_opaque_chip)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            card.country?.let { cc ->
                Text(
                    stringResource(R.string.hop_card_country, cc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.rttMs?.let { ms ->
                Text(
                    stringResource(R.string.hop_rtt, ms),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.address?.let { ip ->
                Text(stringResource(R.string.hop_ip, ip), style = MaterialTheme.typography.bodyMedium)
            }
            card.name?.let { name ->
                Text(stringResource(R.string.hop_ptr, name), style = MaterialTheme.typography.bodyMedium)
            }
            card.asn?.let { asn ->
                Text(stringResource(R.string.hop_asn, asn), style = MaterialTheme.typography.bodyMedium)
            }
            card.org?.let { org ->
                Text(stringResource(R.string.hop_operator, org), style = MaterialTheme.typography.bodyMedium)
            }
            card.registeredTo?.let { name ->
                Text(stringResource(R.string.hop_registered_to, name), style = MaterialTheme.typography.bodyMedium)
            }
            card.prefix?.let { prefix ->
                Text(stringResource(R.string.hop_prefix, prefix), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
