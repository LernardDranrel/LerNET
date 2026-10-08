package app.lernet.ui.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton

/** Shared editor shell; each domain supplies its own graph, list and durable-save actions. */
@Composable
internal fun RouteEditorWorkspace(
    asList: Boolean,
    onListChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    controls: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingAction: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        header()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LerNetDimens.screenPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(false, true).forEachIndexed { index, listMode ->
                    SegmentedButton(
                        selected = asList == listMode,
                        onClick = { if (asList != listMode) onListChange(listMode) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {
                            if (asList == listMode) Icon(LerNetSymbols.check(), contentDescription = null)
                        },
                        label = { Text(stringResource(if (listMode) R.string.route_list else R.string.route_schema)) },
                        modifier = Modifier.lernetButton(),
                    )
                }
            }
            controls()
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            content()
            Box(Modifier.align(Alignment.BottomEnd).padding(LerNetDimens.screenPadding)) { floatingAction() }
        }
        bottomBar()
    }
}
