package app.lernet.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Session state and its primary action. The error or recovery panel may already own that action. */
@Composable
fun ConnectionHeader(
    status: String,
    tone: Color,
    active: Boolean,
    actionLabel: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val summary: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(tone, CircleShape))
            Column {
                Text(status, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("home_state"))
                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val action: @Composable () -> Unit = {
        if (actionLabel != null) {
            val button = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = actionLabel
                stateDescription = status
            }
            if (active) OutlinedButton(onToggle, button, enabled = enabled) { Text(actionLabel) }
            else Button(onToggle, button, enabled = enabled) { Text(actionLabel) }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (maxWidth < 282.dp * LocalDensity.current.fontScale && actionLabel != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { summary(); action() }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { summary() }
                action()
            }
        }
    }
}
