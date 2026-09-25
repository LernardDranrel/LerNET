package app.lernet.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.routing.ConditionKind
import app.lernet.routing.RouteElse

/** Maps compiler / Else / sheet gate tokens to Russian user-facing copy. */
@Composable
internal fun routeFieldErrorText(raw: String): String {
    val message = raw.substringAfter(": ", raw)
    return when (message) {
        RouteElse.MISSING -> stringResource(R.string.else_err_missing)
        RouteElse.NOT_LAST -> stringResource(R.string.else_err_not_last)
        RouteElse.DISABLED -> stringResource(R.string.else_err_disabled)
        RouteElse.EXTRA -> stringResource(R.string.else_err_extra)
        RouteElse.BLANK -> stringResource(R.string.else_err_blank)
        RouteElse.PIPE_AND_FORK -> stringResource(R.string.else_err_pipe_fork)
        RuleSheetGate.EMPTY_BLOCKS -> stringResource(R.string.rule_blocks_required)
        else -> {
            val kind = RuleSheetGate.kindOfEmptyBlock(
                if (raw.contains("block_empty:")) {
                    "block_empty:" + raw.substringAfter("block_empty:")
                } else {
                    message
                },
            )
            if (kind != null) {
                val title = when (kind) {
                    ConditionKind.DOMAIN -> stringResource(R.string.rule_block_domain)
                    ConditionKind.GEOIP -> stringResource(R.string.rule_block_geo)
                    ConditionKind.PRIVATE -> stringResource(R.string.rule_block_private)
                    ConditionKind.CIDR -> stringResource(R.string.rule_block_cidr)
                    ConditionKind.APP -> stringResource(R.string.rule_block_app)
                    ConditionKind.PROCESS -> "Имя процесса"
                }
                stringResource(R.string.rule_block_empty, title)
            } else {
                // Compiler messages are already Russian; drop English field keys.
                message
            }
        }
    }
}
