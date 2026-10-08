package app.lernet.desktop.expert

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.config.policy.ExternalExitKind
import app.lernet.engine.policy.ExitPhase

internal object ExpertColors {
    val background = Color(0xFF0B1019)
    val panel = Color(0xFF151D2B)
    val border = Color(0xFF31405B)
    val text = Color(0xFFF5F7FB)
    val muted = Color(0xFFA8B5CB)
    val blue = Color(0xFF91ABFF)
    val green = Color(0xFF80DEBE)
    val amber = Color(0xFFF5C16C)
    val red = Color(0xFFFFB4AB)
}

@Composable
internal fun ExpertPanel(
    title: String,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.animateContentSize(tween(if (reducedMotion) 0 else 180)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ExpertColors.panel),
        border = BorderStroke(1.dp, ExpertColors.border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    title, Modifier.weight(1f), color = ExpertColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                trailing()
            }
            content()
        }
    }
}

@Composable
internal fun ExpertScrollableColumn(
    modifier: Modifier = Modifier,
    spacing: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content
        )
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun ExpertTag(text: String, color: Color = ExpertColors.blue) {
    Surface(color = color.copy(alpha = .11f), shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = color, fontSize = 12.sp)
    }
}

@Composable
internal fun ExpertMessage(
    title: String,
    body: String,
    warning: Boolean = false,
    error: Boolean = false,
    bodyMaxLines: Int = Int.MAX_VALUE,
) {
    val color = when {
        error -> ExpertColors.red
        warning -> ExpertColors.amber
        else -> ExpertColors.blue
    }
    Surface(color = color.copy(alpha = .09f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color.copy(alpha = .25f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                body, color = ExpertColors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                maxLines = bodyMaxLines, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ExpertEmpty(title: String, explanation: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NetworkGlyph(ExpertColors.muted)
        Spacer(Modifier.height(12.dp))
        Text(title, color = ExpertColors.text, fontWeight = FontWeight.Medium)
        Text(explanation, Modifier.padding(top = 8.dp), color = ExpertColors.muted, fontSize = 13.sp)
    }
}

@Composable
internal fun NetworkGlyph(color: Color = ExpertColors.blue) {
    Canvas(Modifier.size(36.dp).semantics { contentDescription = "Схема сети" }) {
        val root = Offset(size.width * .5f, size.height * .18f)
        val left = Offset(size.width * .2f, size.height * .78f)
        val right = Offset(size.width * .8f, size.height * .78f)
        val middle = size.height * .47f
        listOf(left, right).forEach { endpoint ->
            drawLine(color, root, Offset(root.x, middle), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(root.x, middle), Offset(endpoint.x, middle), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(endpoint.x, middle), endpoint, 2.dp.toPx(), StrokeCap.Round)
        }
        listOf(root, left, right).forEach { drawCircle(color, 4.dp.toPx(), it) }
    }
}

@Composable
internal fun ExpertConfirm(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        onConfirm()
                        true
                    }
                    else -> false
                }
            }
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

internal fun phaseName(phase: ExpertPhase): String = when (phase) {
    ExpertPhase.STOPPED -> "Выключен"
    ExpertPhase.STARTING -> "Создаём TUN"
    ExpertPhase.RUNNING -> "TUN работает"
    ExpertPhase.APPLYING -> "Применяем схему"
    ExpertPhase.STOPPING -> "Останавливаем"
    ExpertPhase.FAILED -> "Нужна проверка"
}

internal fun exitPhaseName(phase: ExitPhase): String = when (phase) {
    ExitPhase.SLEEPING -> "Спит"
    ExitPhase.STARTING -> "Просыпается"
    ExitPhase.READY -> "Работает"
    ExitPhase.DEGRADED -> "Проверяем связь"
    ExitPhase.FAILED -> "Недоступен"
    ExitPhase.DRAINING -> "Завершает соединения"
}

internal fun exitPhaseColor(phase: ExitPhase): Color = when (phase) {
    ExitPhase.READY -> ExpertColors.green
    ExitPhase.SLEEPING -> ExpertColors.muted
    ExitPhase.STARTING, ExitPhase.DEGRADED, ExitPhase.DRAINING -> ExpertColors.amber
    ExitPhase.FAILED -> ExpertColors.red
}

internal fun interfaceBoundExit(exit: ExpertExit, state: ExpertUiState): Boolean =
    state.profiles.any { it.id == exit.profileId && it.interfaceBinding != null } ||
        state.externalProfiles.any { it.id == exit.profileId && it.request.kind == ExternalExitKind.CORPORATE_INTERFACE }

internal fun exitDisplayName(exit: ExpertExit, state: ExpertUiState): String = when {
    exit.phase != ExitPhase.READY -> exitPhaseName(exit.phase)
    interfaceBoundExit(exit, state) -> "Интерфейсный выход подготовлен"
    exit.latencyMs?.let { it > 0 } != true -> "Транспорт создан"
    else -> exitPhaseName(exit.phase)
}

internal fun exitDisplayColor(exit: ExpertExit, state: ExpertUiState): Color =
    if (exit.phase == ExitPhase.READY && (interfaceBoundExit(exit, state) || exit.latencyMs?.let { it > 0 } != true)) {
        ExpertColors.blue
    } else {
        exitPhaseColor(exit.phase)
    }

@Composable
internal fun ExitTransportQualification(exit: ExpertExit, state: ExpertUiState) {
    if (interfaceBoundExit(exit, state) && exit.phase == ExitPhase.READY) {
        ExpertMessage(
            "Готовность не равна доступности",
            "Состояние выхода описывает созданный транспорт с привязкой к адаптеру. " +
                "Оно не подтверждает доступ к интернету или ресурсам компании. Публичный HTTPS автоматически не проверяется: " +
                "корпоративный VPN может давать доступ только к внутренней сети. Ошибка отдельного подключения может " +
                "не изменить готовность транспорта; её причину смотрите в «Событиях».",
            warning = true
        )
    } else if (exit.phase == ExitPhase.READY && exit.latencyMs?.let { it > 0 } != true) {
        Text("Транспорт подготовлен; подтверждённой проверки пути ещё нет.", color = ExpertColors.muted, fontSize = 13.sp)
    }
}

@Composable
internal fun DomainRecognitionQualification() {
    ExpertMessage(
        "Границы правил сайтов",
        "Правило сайта действует, когда ядро распознало его имя через DNS, SNI или HTTP. " +
            "При собственном DoH приложения, ECH или общем IP нескольких сайтов имя может быть неизвестно либо неоднозначно; " +
            "ветка тогда может не совпасть. Если путь по умолчанию — «Напрямую», такой трафик может уйти обычной сетью. " +
            "Для строгого запрета прямого выхода задайте защищённую ветку по программе или IP/подсети.",
        warning = true
    )
}

internal fun formatTraffic(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} КБ"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} МБ"
}

@Composable
internal fun ExpertActions(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPrimary, enabled = enabled) { Text(primary) }
        OutlinedButton(onClick = onSecondary) { Text(secondary) }
    }
}
