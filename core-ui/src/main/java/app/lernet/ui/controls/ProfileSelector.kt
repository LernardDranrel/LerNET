package app.lernet.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ProfileSelector(
    name: String, subtitle: String, changeLabel: String, rulesLabel: String,
    onChoose: (() -> Unit)?, onRules: (() -> Unit)?,
) {
    Surface(modifier = Modifier.fillMaxWidth().testTag("home_profile_line"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val select = if (onChoose == null) Modifier else Modifier
                .testTag("home_profile_change").clickable(onClickLabel = changeLabel, role = Role.Button, onClick = onChoose)
            Row(Modifier.weight(1f).then(select).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onChoose != null) Text("▾", Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.primary)
            }
            if (onRules != null) TextButton(onRules, Modifier.heightIn(min = 48.dp).testTag("home_profile_rules")) {
                Text(rulesLabel)
            }
        }
    }
}
