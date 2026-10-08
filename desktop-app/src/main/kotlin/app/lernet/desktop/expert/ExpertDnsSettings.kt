package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lernet.routing.policy.PolicyDnsMode
import app.lernet.routing.policy.PolicyDnsSettings

@Composable
internal fun ExpertDnsSettings(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, onDismiss: () -> Unit) {
    val original = remember { state.draft.dns }
    var mode by remember { mutableStateOf(original.mode) }
    var server by remember { mutableStateOf(original.server) }
    var pending by remember { mutableStateOf<ExpertDraftSubmission?>(null) }
    val chosen = PolicyDnsSettings(mode, server.trim())
    val changedElsewhere = state.draft.dns != original && state.draft.dns != chosen
    LaunchedEffect(state.draft, state.error, state.events.lastOrNull()?.id, pending) {
        when (pending?.response(state)) {
            ExpertDraftResponse.CONFIRMED -> {
                pending = null
                onDismiss()
            }
            ExpertDraftResponse.FAILED -> pending = null
            ExpertDraftResponse.WAITING, null -> Unit
        }
    }
    fun save() {
        val updated = state.draft.copy(dns = chosen)
        if (updated == state.draft && state.error == null) {
            onDismiss()
        } else {
            pending = ExpertDraftSubmission.capture(updated, state)
            onIntent(ExpertIntent.EditPolicy(updated))
        }
    }
    val readOnly = pending != null || state.busy
    ExpertModal(
        "DNS Expert", onDismiss, ::save,
        canSave = chosen.isValid() && !changedElsewhere && !readOnly,
        saveText = "Сохранить в черновик"
    ) {
        ExpertMessage(
            "DNS прямого трафика",
            "По умолчанию сохраняется выбор DNS исходной сети. При смене подключения учитываются его настройки. " +
                "Автоматической подмены публичным сервером нет.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == PolicyDnsMode.SYSTEM, {
                mode = PolicyDnsMode.SYSTEM
            }, label = { Text("Исходная сеть") }, enabled = !readOnly)
            FilterChip(mode == PolicyDnsMode.CUSTOM, { mode = PolicyDnsMode.CUSTOM }, label = { Text("Свой сервер") }, enabled = !readOnly)
        }
        if (mode == PolicyDnsMode.CUSTOM) {
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(),
                label = { Text("IPv4-адрес DNS-сервера") }, singleLine = true,
                isError = !chosen.isValid(), enabled = !readOnly
            )
            Text(
                "Прямые DNS-запросы будут отправляться этому серверу. При его недоступности появится ошибка; " +
                    "резервный сервер не подставляется.",
            )
        }
        Text(
            "Настройка касается обычного DNS прямых веток. DNS защищённых веток идёт через их выход. " +
                "Адрес самого VPN-сервера разрешается через исходную сеть. " +
                "Приложения со своим DoH могут выбирать DNS самостоятельно.",
        )
        Text("Изменение попадёт в черновик. Для работающего туннеля сохраните и примените схему.")
        if (changedElsewhere) Text("DNS уже изменён в другом окне. Закройте форму и откройте её снова.", color = ExpertColors.red)
    }
}
