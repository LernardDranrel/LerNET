package app.lernet.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.engine.net.observation.*

@Composable
internal fun ReadingDisclosure(expanded: Boolean, reducedMotion: Boolean, body: @Composable () -> Unit) {
    if (reducedMotion) { if (expanded) body() }
    else AnimatedVisibility(expanded, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) { body() }
}

@Composable
internal fun ReadingMark(mark: String, tint: Color) {
    Box(Modifier.size(32.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Text(mark, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
internal fun ReadingBlock(title: String, text: String) {
    if (text.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun ReadingSteps(steps: List<String>) {
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Что проверить дальше", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp), fontSize = 12.sp)
                Text(step, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun PageReadingGuide(title: String, steps: List<String>, reducedMotion: Boolean) {
    var expanded by remember(title) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "План чтения раскрыт" else "Показать план чтения" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingMark("?", MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("С чего начать в разделе «$title»", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(steps.firstOrNull().orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "↑" else "↓", color = MaterialTheme.colorScheme.primary)
            }
            ReadingDisclosure(expanded, reducedMotion) {
                Column(Modifier.padding(top = 14.dp)) { ReadingSteps(steps) }
            }
        }
    }
}

@Composable
internal fun SourceReadingGuide(source: ObservationSource, reducedMotion: Boolean) {
    var expanded by remember(source.id) { mutableStateOf(false) }
    val guide = ObservationGuide.source(source.id)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReadingBlock("Результат чтения", ObservationGuide.availability(source))
        if (source.detail.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
                SelectionContainer { Column(Modifier.padding(12.dp)) {
                    Text("Причина, указанная источником", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Text(source.detail, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                } }
            }
        }
        TextButton({ expanded = !expanded }, Modifier.testTag("network-guide-${source.id}")) {
            Text(if (expanded) "Скрыть инструкцию ↑" else "Как читать эти данные ↓")
        }
        ReadingDisclosure(expanded, reducedMotion) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ReadingBlock("На что смотрим", guide.inspect)
                ReadingBlock("Когда стоит проверить", guide.attention)
                ReadingBlock("Чего эти данные не доказывают", guide.limits)
            }
        }
    }
}

@Composable
internal fun ExplainedFields(fields: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton({ expanded = !expanded }) { Text(if (expanded) "Скрыть технические поля ↑" else "Поля и их смысл · ${fields.size} ↓") }
        if (expanded) fields.forEach { (key, value) ->
            val guide = ObservationGuide.field(key)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(guide.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    SelectionContainer { Text(ObservationGuide.fieldValue(key, value), fontSize = 14.sp) }
                    Text(guide.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    SelectionContainer { Text("Поле ОС: $key${if (ObservationGuide.fieldValue(key, value) != value) " · исходное: $value" else ""}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindingFilters(findings: List<NetworkFinding>, selected: FindingKind?, onSelect: (FindingKind?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelect(null) }, { Text("Все · ${findings.size}") })
        listOf(FindingKind.POTENTIAL_CONFLICT to "Проверить", FindingKind.FACT to "Факты", FindingKind.INSUFFICIENT_DATA to "Пробелы данных").forEach { (kind, label) ->
            val count = findings.count { it.kind == kind }
            FilterChip(selected == kind, { onSelect(if (selected == kind) null else kind) }, { Text("$label · $count") })
        }
    }
}
