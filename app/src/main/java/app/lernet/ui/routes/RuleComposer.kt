package app.lernet.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.PatternSign
import app.lernet.routing.RuleConditions
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.lernetButton

@Composable
internal fun RuleComposer(node: RuleNodeRecord, onIntent: (RouteEditorIntent) -> Unit) {
    val conditions = node.shownConditions()
    val commit = { next: RuleConditions -> onIntent(RouteEditorIntent.Update(node.withConditions(next))) }
    ConditionsComposer(conditions, commit)
}

/** Shared condition controls; adapters retain the identity and storage of each editor's node. */
@Composable
internal fun ConditionsComposer(
    conditions: RuleConditions,
    onChange: (RuleConditions) -> Unit,
    includeProcess: Boolean = false,
    requireBlocks: Boolean = true,
) {
    val commit = onChange
    Column(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        JoinToggle(conditions.join) { commit(conditions.copy(join = it)) }
        conditions.blocks.forEachIndexed { index, block ->
            key(index, block.kind) {
                BlockCard(
                    block = block,
                    onChange = { updated ->
                        commit(conditions.copy(blocks = conditions.blocks.replaceAt(index, updated)))
                    },
                    onRemove = {
                        commit(conditions.copy(blocks = conditions.blocks.filterIndexed { i, _ -> i != index }))
                    },
                )
            }
        }
        AddBlockMenu(includeProcess) { kind ->
            commit(conditions.copy(blocks = conditions.blocks + ConditionBlock(kind, emptyList())))
        }
        if (requireBlocks && conditions.blocks.isEmpty()) {
            Text(
                stringResource(R.string.rule_blocks_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.rule_block_model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JoinToggle(join: MatchJoin, onChange: (MatchJoin) -> Unit) {
    val modes = listOf(MatchJoin.OR, MatchJoin.AND)
    Text(
        stringResource(R.string.rule_join_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = join == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.lernetButton(),
            ) {
                Text(stringResource(if (mode == MatchJoin.OR) R.string.rule_join_or else R.string.rule_join_and))
            }
        }
    }
}

@Composable
private fun BlockCard(block: ConditionBlock, onChange: (ConditionBlock) -> Unit, onRemove: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    blockTitle(block.kind),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove, modifier = Modifier.lernetButton()) {
                    Icon(LerNetSymbols.delete(), contentDescription = null)
                    Text(stringResource(R.string.rule_remove_block))
                }
            }
            BlockValues(block, onChange)
        }
    }
}

@Composable
private fun BlockValues(block: ConditionBlock, onChange: (ConditionBlock) -> Unit) {
    when (block.kind) {
        ConditionKind.DOMAIN -> PatternChipField(
            values = block.values,
            addLabel = stringResource(R.string.rule_add_domain),
            fieldLabel = stringResource(R.string.field_domain_pattern),
            help = stringResource(R.string.field_domains_help),
            onChange = { onChange(block.copy(values = it)) },
        )
        ConditionKind.CIDR -> PatternChipField(
            values = block.values,
            addLabel = stringResource(R.string.rule_add_cidr),
            fieldLabel = stringResource(R.string.field_cidr_pattern),
            help = null,
            onChange = { onChange(block.copy(values = it)) },
        )
        ConditionKind.APP -> {
            AppSelectionField(block.values) { onChange(block.copy(values = it)) }
            PatternChipField(
                values = block.values,
                addLabel = stringResource(R.string.rule_add_app),
                fieldLabel = stringResource(R.string.field_app_pattern),
                help = stringResource(R.string.rule_app_manual_hint),
                onChange = { onChange(block.copy(values = it)) },
            )
        }
        ConditionKind.PROCESS -> PatternChipField(
            values = block.values,
            addLabel = stringResource(R.string.rule_add_process),
            fieldLabel = stringResource(R.string.expert_condition_process),
            help = stringResource(R.string.expert_inactive_android),
            onChange = { onChange(block.copy(values = it)) },
        )
        ConditionKind.GEOIP -> CountryPicker(block.values) { onChange(block.copy(values = it)) }
        ConditionKind.PRIVATE -> PrivateNetworkPicker(block.values) { onChange(block.copy(values = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PatternChipField(
    values: List<String>,
    addLabel: String,
    fieldLabel: String,
    help: String?,
    onChange: (List<String>) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val filled = values.filter { PatternSign.body(it).isNotEmpty() }
    LaunchedEffect(draft != null) {
        if (draft != null) focus.requestFocus()
    }
    val commitDraft: () -> Unit = {
        val body = draft?.trim().orEmpty()
        draft = null
        val additions = body.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        if (additions.isNotEmpty()) onChange(filled + additions)
    }
    Text(
        stringResource(R.string.field_negate_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (filled.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            filled.forEachIndexed { index, value ->
                SignedChip(
                    text = PatternSign.body(value),
                    negated = PatternSign.negated(value),
                    onToggle = {
                        onChange(
                            filled.mapIndexed { itemIndex, item ->
                                if (itemIndex == index) PatternSign.signed(item, !PatternSign.negated(item)) else item
                            }
                        )
                    },
                    onRemove = { onChange(filled.filterIndexed { itemIndex, _ -> itemIndex != index }) },
                )
            }
        }
    }
    val editing = draft
    if (editing != null) {
        OutlinedTextField(
            value = editing,
            onValueChange = { draft = it },
            label = { Text(fieldLabel) },
            supportingText = help?.let { hint -> { Text(hint) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitDraft() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            trailingIcon = {
                IconButton(
                    onClick = commitDraft,
                    modifier = Modifier.padding(end = 8.dp).size(44.dp)
                        .background(LerNetOk, RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        LerNetSymbols.check(), contentDescription = stringResource(R.string.done),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
        )
    } else {
        TextButton(onClick = { draft = "" }, modifier = Modifier.fillMaxWidth().lernetButton()) {
            Icon(LerNetSymbols.add(), contentDescription = null)
            Text(addLabel)
        }
    }
}

@Composable
internal fun SignedChip(
    text: String,
    negated: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = if (negated) stringResource(R.string.field_geo_negated, text) else text
    val colors = if (negated) {
        InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        InputChipDefaults.inputChipColors(
            containerColor = LerNetOk.copy(alpha = 0.22f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = LerNetOk.copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    val removeDescription = stringResource(R.string.rule_remove_pattern)
    InputChip(
        selected = true,
        onClick = onToggle,
        label = { Text(label) },
        colors = colors,
        trailingIcon = {
            Icon(
                LerNetSymbols.close(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).semantics { contentDescription = removeDescription }.clickable(onClick = onRemove),
            )
        },
    )
}

@Composable
private fun AddBlockMenu(includeProcess: Boolean, onAdd: (ConditionKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().lernetButton()) {
            Icon(LerNetSymbols.add(), contentDescription = null)
            Text(stringResource(R.string.rule_add_block))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true, clippingEnabled = true),
        ) {
            ConditionKind.entries.filter { includeProcess || it != ConditionKind.PROCESS }.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(blockTitle(kind)) },
                    onClick = {
                        open = false
                        onAdd(kind)
                    },
                )
            }
        }
    }
}

@Composable
private fun blockTitle(kind: ConditionKind): String = when (kind) {
    ConditionKind.DOMAIN -> stringResource(R.string.rule_block_domain)
    ConditionKind.GEOIP -> stringResource(R.string.rule_block_geo)
    ConditionKind.PRIVATE -> stringResource(R.string.rule_block_private)
    ConditionKind.CIDR -> stringResource(R.string.rule_block_cidr)
    ConditionKind.APP -> stringResource(R.string.rule_block_app)
    ConditionKind.PROCESS -> stringResource(R.string.expert_condition_process)
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, item -> if (i == index) value else item }
