package app.lernet.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.compile.FieldSource
import app.lernet.engine.compile.FieldView
import app.lernet.engine.compile.OverrideReason
import app.lernet.engine.compile.OwnedOutbound
import app.lernet.engine.compile.TruthFieldId
import app.lernet.ui.theme.LerNetBlack
import app.lernet.ui.theme.LerNetWarn

@Composable
fun ConfigOverrideRow(
    field: FieldView,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (field.overridden && field.source == FieldSource.SYSTEM) {
            OverriddenValues(field)
        } else {
            Text(displayValue(field.id, shownValue(field)), style = MaterialTheme.typography.bodyMedium)
            if (field.source == FieldSource.GLOBAL || field.source == FieldSource.SYSTEM) {
                Surface(color = LerNetWarn, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (field.source == FieldSource.GLOBAL) {
                            stringResource(R.string.cfg_from_global)
                        } else {
                            stringResource(R.string.cfg_from_system)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = LerNetBlack,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverriddenValues(field: FieldView) {
    val struck = field.profileValue.ifBlank { field.globalValue.orEmpty() }
    if (struck.isNotBlank()) {
        Text(
            displayValue(field.id, struck),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
            modifier = Modifier.testTag("cfg-profile-struck"),
        )
    }
    Surface(color = LerNetWarn, shape = RoundedCornerShape(8.dp)) {
        Text(
            stringResource(R.string.cfg_overridden),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = LerNetBlack,
        )
    }
    Text(
        stringResource(R.string.cfg_effective, displayValue(field.id, field.effectiveValue)),
        style = MaterialTheme.typography.bodyMedium,
    )
    val reason = field.reason
    if (reason != null) {
        Text(
            reasonText(reason, field.reasonDetail.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun reasonText(reason: OverrideReason, detail: String): String =
    when (reason) {
        OverrideReason.DNS_DROPPED_PROXIED -> stringResource(R.string.cfg_reason_dns_dropped, detail)
        OverrideReason.DNS_DROPPED_DIRECT_DETOUR -> stringResource(R.string.cfg_reason_detour, detail)
        OverrideReason.XMUX_STAMPED -> stringResource(R.string.cfg_reason_xmux, detail)
        OverrideReason.LOG_RAISED -> stringResource(R.string.cfg_reason_log)
    }

@Composable
private fun displayValue(id: TruthFieldId, value: String): String {
    if (value.isBlank()) return stringResource(R.string.cfg_unset)
    if (id != TruthFieldId.REALITY) return value
    return when (value) {
        OwnedOutbound.REALITY_PRESENT -> stringResource(R.string.cfg_reality_set)
        OwnedOutbound.REALITY_ABSENT -> stringResource(R.string.cfg_reality_none)
        else -> value
    }
}

private fun shownValue(field: FieldView): String =
    field.effectiveValue.ifBlank { field.profileValue.ifBlank { field.globalValue.orEmpty() } }
