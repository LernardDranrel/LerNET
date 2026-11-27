package app.lernet.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

sealed interface TemplateAction {
    data class Select(val id: String) : TemplateAction
    data class Create(val name: String) : TemplateAction
    data class Rename(val id: String, val name: String) : TemplateAction
    data class Delete(val id: String) : TemplateAction
}

private enum class TemplateDialog { CREATE, RENAME, DELETE }

/** Edits the selected workspace; publishing its policy is a separate action at the call site. */
@Composable
fun ExpertTemplatePicker(
    choices: List<Pair<String, String>>,
    selectedId: String,
    enabled: Boolean,
    onChange: suspend (TemplateAction) -> String?,
    modifier: Modifier = Modifier,
    protectedId: String? = null,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val current = choices.firstOrNull { it.first == selectedId }?.second
    var choosing by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var pickRemoval by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<TemplateDialog?>(null) }
    var target by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val available = enabled && !busy && current != null
    val removable = choices.filter { it.first != selectedId && it.first != protectedId }
    fun submit(action: TemplateAction) {
        if (!available) return
        busy = true
        error = null
        scope.launch {
            try {
                error = onChange(action)
                if (error == null) dialog = null
            } finally {
                busy = false
            }
        }
    }
    fun openDialog(next: TemplateDialog, id: String = selectedId) {
        managing = false
        pickRemoval = false
        target = id
        name = if (next == TemplateDialog.RENAME) current.orEmpty() else ""
        error = null
        dialog = next
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    { choosing = true }, enabled = available,
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 32.dp else 48.dp)
                        .semantics { contentDescription = "Выбрать шаблон" }.testTag("expert_template_picker"),
                    contentPadding = if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        else androidx.compose.material3.ButtonDefaults.ContentPadding,
                    shape = if (compact) androidx.compose.foundation.shape.RoundedCornerShape(7.dp) else MaterialTheme.shapes.medium,
                ) {
                    Text("Шаблон", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current ?: "…", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("▾")
                }
                if (dialog == null) DropdownMenu(choosing, { choosing = false }) {
                    choices.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = { if (id == selectedId) Text("✓") },
                            onClick = {
                                choosing = false
                                if (id != selectedId) submit(TemplateAction.Select(id))
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Создать копию…") }, onClick = {
                        choosing = false
                        openDialog(TemplateDialog.CREATE)
                    })
                    if (compact) DropdownMenuItem(text = { Text("Действия с шаблонами…") }, onClick = {
                        choosing = false
                        pickRemoval = false
                        managing = true
                    })
                }
            }
            Box {
                if (!compact) TextButton(
                    { managing = true; pickRemoval = false }, enabled = available,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .semantics { contentDescription = "Действия с шаблонами" }.testTag("expert_template_actions"),
                ) { Text("⋯", style = MaterialTheme.typography.titleLarge) }
                if (dialog == null) DropdownMenu(managing, { managing = false }) {
                    if (pickRemoval) {
                        removable.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { openDialog(TemplateDialog.DELETE, id) })
                        }
                    } else {
                        DropdownMenuItem(text = { Text("Создать копию…") }, onClick = { openDialog(TemplateDialog.CREATE) })
                        DropdownMenuItem(text = { Text("Переименовать…") }, onClick = { openDialog(TemplateDialog.RENAME) })
                        DropdownMenuItem(text = { Text("Удалить другой шаблон…") },
                            enabled = removable.isNotEmpty(), onClick = { pickRemoval = true })
                    }
                }
            }
        }
        if (dialog == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    val editing = dialog ?: return
    val targetName = choices.firstOrNull { it.first == target }?.second.orEmpty()
    val title = when (editing) {
        TemplateDialog.CREATE -> "Копия шаблона"
        TemplateDialog.RENAME -> "Переименовать шаблон"
        TemplateDialog.DELETE -> "Удалить «$targetName»?"
    }
    val actionLabel = when (editing) {
        TemplateDialog.CREATE -> "Создать"
        TemplateDialog.RENAME -> "Сохранить"
        TemplateDialog.DELETE -> "Удалить"
    }
    ExpertFormDialog(
        onDismissRequest = { if (!busy) { dialog = null; error = null } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (editing) {
                    TemplateDialog.CREATE -> Text("Скопируются сохранённая схема и черновик «$targetName».")
                    TemplateDialog.RENAME -> Unit
                    TemplateDialog.DELETE -> Text("Сохранённая схема и черновик этого шаблона будут удалены.")
                }
                if (editing != TemplateDialog.DELETE) {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Название шаблона") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("expert_template_name"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                submit(when (editing) {
                    TemplateDialog.CREATE -> TemplateAction.Create(name.trim())
                    TemplateDialog.RENAME -> TemplateAction.Rename(target, name.trim())
                    TemplateDialog.DELETE -> TemplateAction.Delete(target)
                })
            }, enabled = available && (editing == TemplateDialog.DELETE || name.isNotBlank())) { Text(actionLabel) }
        },
        dismissButton = {
            TextButton({ dialog = null; error = null }, enabled = !busy) { Text("Отмена") }
        },
    )
}
