package app.lernet.ui.importcfg

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.parse.FieldError
import app.lernet.config.parse.GuessedMode
import app.lernet.config.parse.ImportGuess
import app.lernet.config.parse.ImportHint
import app.lernet.config.parse.SoftHint
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.layout.rememberThumbZoneBottomPadding
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import app.lernet.ui.theme.lernetPrimaryAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportUiState,
    onIntent: (ImportIntent) -> Unit,
    onBack: () -> Unit,
    events: Flow<ImportEvent>,
    onOpenRoutes: (String) -> Unit,
    onSaved: (List<String>) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val busyExitMessage = stringResource(R.string.import_busy_exit)
    var confirmLeave by remember { mutableStateOf(false) }
    val hasDraft = state.busy || state.input.isNotBlank()
    val requestBack: () -> Unit = {
        when {
            state.busy -> scope.launch { snackbar.showSnackbar(busyExitMessage) }
            hasDraft -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(enabled = hasDraft) { requestBack() }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.import_unsaved_title)) },
            text = { Text(stringResource(R.string.import_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) {
                    Text(stringResource(R.string.routes_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val clipboard = LocalClipboardManager.current
    var clipOffer by remember { mutableStateOf<String?>(null) }
    ObserveImportClipboard(clipboard) { clipOffer = it }
    ObserveImportSaved(events, snackbar, onOpenRoutes, onSaved)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ImportTopBar(requestBack) },
    ) { padding ->
        ImportForm(
            state = state,
            clipOffer = clipOffer,
            onClearOffer = { clipOffer = null },
            onIntent = onIntent,
            onPaste = {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isNotBlank()) onIntent(ImportIntent.SetInput(text))
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun ObserveImportClipboard(
    clipboard: ClipboardManager,
    onOffer: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        val raw = clipboard.getText()?.text?.trim().orEmpty()
        if (looksLikeConfig(raw)) onOffer(raw)
    }
}

@Composable
private fun ObserveImportSaved(
    events: Flow<ImportEvent>,
    snackbar: SnackbarHostState,
    onOpenRoutes: (String) -> Unit,
    onSaved: (List<String>) -> Unit,
) {
    val added = stringResource(R.string.import_added)
    val openRoutes = stringResource(R.string.open_routes)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is ImportEvent.Saved -> {
                    onSaved(event.profileIds)
                    val result = snackbar.showSnackbar(message = added, actionLabel = openRoutes)
                    if (result == SnackbarResult.ActionPerformed && event.firstProfileId != null) {
                        onOpenRoutes(event.firstProfileId)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.import_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ImportSubmitBar(state: ImportUiState, onIntent: (ImportIntent) -> Unit) {
    val label = stringResource(if (state.busy) R.string.importing else R.string.import_action)
    val bottomGap = rememberThumbZoneBottomPadding()
    Button(
        onClick = { onIntent(ImportIntent.Submit) },
        enabled = !state.busy && state.input.isNotBlank() && state.effectiveMode != null,
        modifier = Modifier
            .padding(start = LerNetDimens.screenPadding, end = LerNetDimens.screenPadding, top = LerNetDimens.screenPadding)
            .padding(bottom = bottomGap)
            .lernetPrimaryAction()
            .semantics { contentDescription = label },
        shape = MaterialTheme.shapes.large,
    ) {
        if (state.busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(LerNetDimens.itemGap))
        }
        Text(label, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportForm(
    state: ImportUiState,
    clipOffer: String?,
    onClearOffer: () -> Unit,
    onIntent: (ImportIntent) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberCompactMetrics()
    Column(
        modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(metrics.gutter),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Text(stringResource(R.string.import_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImportFields(state, metrics.compact, onIntent, onPaste)
        ImportGuessLine(state.guess)
        Text(
            stringResource(R.string.import_override),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImportModeChips(state, onIntent)
        ImportFeedback(state, clipOffer, onClearOffer, onIntent)
        ImportSubmitBar(state, onIntent)
    }
}

@Composable
private fun ImportFields(
    state: ImportUiState,
    compact: Boolean,
    onIntent: (ImportIntent) -> Unit,
    onPaste: () -> Unit,
) {
    OutlinedTextField(
        value = state.displayName,
        onValueChange = { onIntent(ImportIntent.SetDisplayName(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !state.busy,
        label = { Text(stringResource(R.string.profile_name)) },
    )
    Text(
        stringResource(R.string.profile_name_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.input,
        onValueChange = { onIntent(ImportIntent.SetInput(it)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 120.dp else 160.dp),
        label = { Text(modePlaceholder(state.effectiveMode)) },
        enabled = !state.busy,
        isError = state.errors.isNotEmpty(),
        shape = MaterialTheme.shapes.large,
        trailingIcon = {
            IconButton(onClick = onPaste, enabled = !state.busy) {
                Icon(LerNetSymbols.paste(), contentDescription = stringResource(R.string.paste_clipboard))
            }
        },
    )
}

@Composable
private fun ImportFeedback(
    state: ImportUiState,
    clipOffer: String?,
    onClearOffer: () -> Unit,
    onIntent: (ImportIntent) -> Unit,
) {
    clipOffer?.let { offer ->
        FilterChip(
            selected = false,
            enabled = !state.busy,
            onClick = {
                onIntent(ImportIntent.SetInput(offer))
                onClearOffer()
            },
            label = { Text(stringResource(R.string.clipboard_offer)) },
        )
    }
    state.errors.forEach { error ->
        Text(importErrorText(error), color = MaterialTheme.colorScheme.error)
    }
    AnimatedVisibility(visible = state.done) {
        Text(stringResource(R.string.import_saved, state.savedCount), color = MaterialTheme.colorScheme.secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportModeChips(state: ImportUiState, onIntent: (ImportIntent) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        ImportMode.entries.forEach { mode ->
            FilterChip(
                selected = state.effectiveMode == mode,
                enabled = !state.busy,
                onClick = { onIntent(ImportIntent.SetMode(mode)) },
                label = { Text(modeLabel(mode)) },
                modifier = Modifier.lernetButton(),
            )
        }
    }
}

@Composable
private fun ImportGuessLine(guess: ImportGuess) {
    val text = guessLabel(guess)
    if (text.isEmpty()) return
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun guessLabel(guess: ImportGuess): String = when {
    guess.needsFetch && guess.mode == null -> stringResource(R.string.import_guess_checking)
    guess.soft == SoftHint.MAYBE_SUBSCRIPTION -> stringResource(R.string.import_guess_maybe)
    guess.mode == GuessedMode.VLESS -> stringResource(R.string.import_guess_vless)
    guess.mode == GuessedMode.JSON_PASTE -> stringResource(R.string.import_guess_json)
    guess.mode == GuessedMode.JSON_URL -> stringResource(R.string.import_guess_json_url)
    guess.mode == GuessedMode.SUBSCRIPTION && guess.document -> stringResource(R.string.import_guess_doc)
    guess.mode == GuessedMode.SUBSCRIPTION -> stringResource(R.string.import_guess_sub)
    else -> ""
}

@Composable
private fun modeLabel(mode: ImportMode): String =
    when (mode) {
        ImportMode.VLESS -> stringResource(R.string.import_vless)
        ImportMode.JSON_URL -> stringResource(R.string.import_json_url)
        ImportMode.JSON_PASTE -> stringResource(R.string.import_json_paste)
        ImportMode.SUBSCRIPTION -> stringResource(R.string.import_subscription)
    }

@Composable
private fun modePlaceholder(mode: ImportMode?): String =
    when (mode) {
        ImportMode.VLESS -> stringResource(R.string.hint_vless)
        ImportMode.JSON_URL -> stringResource(R.string.hint_json_url)
        ImportMode.JSON_PASTE -> stringResource(R.string.hint_json_paste)
        ImportMode.SUBSCRIPTION -> stringResource(R.string.hint_subscription)
        null -> stringResource(R.string.import_hint)
    }

@Composable
private fun importErrorText(error: FieldError): String {
    val field = when {
        error.field == "uuid" -> stringResource(R.string.import_field_uuid)
        error.field == "server" -> stringResource(R.string.import_field_server)
        error.field == "link" -> stringResource(R.string.import_field_link)
        error.field == "json" -> stringResource(R.string.import_field_json)
        error.field == "jsonUrl" -> stringResource(R.string.import_field_json_url)
        error.field == "subscription" -> stringResource(R.string.import_field_subscription)
        error.field == "subscriptionUrl" -> stringResource(R.string.import_field_subscription_url)
        error.field == "outbounds" -> stringResource(R.string.import_field_outbounds)
        error.field.startsWith("line[") && error.field.endsWith("]") -> {
            val index = error.field.removePrefix("line[").removeSuffix("]")
            stringResource(R.string.import_field_line, index)
        }
        else -> error.field
    }
    return stringResource(R.string.import_error_line, field, error.message)
}

internal fun looksLikeConfig(raw: String): Boolean = ImportHint.detect(raw).mode != null
