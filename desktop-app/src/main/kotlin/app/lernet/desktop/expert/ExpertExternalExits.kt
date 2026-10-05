package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import java.util.UUID

internal fun externalKindName(kind: ExternalExitKind): String = when (kind) {
    ExternalExitKind.SOCKS5 -> "SOCKS5-прокси"
    ExternalExitKind.HTTP -> "HTTP-прокси"
    ExternalExitKind.CORPORATE_INTERFACE -> "Системный VPN / интерфейс"
}

private fun externalEndpoint(request: ExternalExitRequest): String =
    "${if (':' in request.host) "[${request.host}]" else request.host}:${request.port}"

internal data class ExpertExternalReference(val scope: PolicyScope, val title: String)

internal fun externalProfileReferences(state: ExpertUiState, profileId: String): List<ExpertExternalReference> = buildList {
    (listOf(state.draft.device) + state.draft.trees).forEach { tree ->
        if ((tree.defaultTarget as? PolicyTarget.Profile)?.id == profileId) {
            add(ExpertExternalReference(tree.scope, "Путь по умолчанию"))
        }
        tree.nodes.filter { (it.target as? PolicyTarget.Profile)?.id == profileId }.forEach { node ->
            add(ExpertExternalReference(tree.scope, node.title.ifBlank { "Без названия" }))
        }
    }
    state.draft.channels.filter { (it.target as? PolicyTarget.Profile)?.id == profileId }.forEach { channel ->
        add(ExpertExternalReference(channel.owner, "Канал: ${channel.name}"))
    }
}

/** Immutable profile definitions are listed separately from observed running exits. */
internal fun LazyListScope.externalProfileDefinitions(
    state: ExpertUiState,
    details: (ExpertExternalProfile) -> Unit,
    edit: (ExpertExternalProfile) -> Unit,
    importedInterfaceDetails: (ExpertProfile) -> Unit = {},
) {
    val importedInterfaces = state.profiles.filter { profile ->
        profile.interfaceBinding != null && state.externalProfiles.none { it.id == profile.id }
    }
    if (state.externalProfiles.isNotEmpty() || importedInterfaces.isNotEmpty()) {
        item {
            Text("Настроенные внешние выходы", color = ExpertColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Сохранённый профиль ещё не означает поднятое соединение. Его можно выбрать целью в схеме или поместить в папку профилей.",
                color = ExpertColors.muted, fontSize = 13.sp
            )
        }
    }
    items(state.externalProfiles, key = { "external:${it.id}" }) { profile ->
        ExpertPanel(profile.request.name, trailing = { ExpertTag(externalKindName(profile.request.kind)) }) {
            if (profile.request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
                Text("Привязка: ${profile.request.binding?.name ?: "не передана"}", color = ExpertColors.muted)
                val current = state.interfaces.firstOrNull { sameBinding(it.binding, profile.request.binding) }
                if (current == null || !current.eligible) {
                    ExpertMessage(
                        "Интерфейс сейчас не подтверждён",
                        current?.reason ?: "Сохранённая привязка будет проверена перед каждым соединением. " +
                            "Она не заменяется обычным адаптером.",
                        warning = true
                    )
                }
            } else {
                Text(externalEndpoint(profile.request), color = ExpertColors.muted)
                Text(
                    if (profile.request.username.isEmpty()) "Без авторизации" else "Авторизация настроена",
                    color = ExpertColors.muted, fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { details(profile) }) { Text("Сведения и привязки") }
                OutlinedButton(
                    onClick = { edit(profile) },
                    enabled = !state.busy &&
                        state.externalSavePending == null
                ) { Text("Изменить профиль") }
            }
        }
    }
    items(importedInterfaces, key = { "imported-interface:${it.id}" }) { profile ->
        ExpertPanel(profile.name, trailing = { ExpertTag("Расширенный интерфейсный выход", ExpertColors.muted) }) {
            Text("Сохранённый адаптер: ${profile.interfaceBinding?.name}", color = ExpertColors.text)
            Text(
                "В профиле есть расширенные настройки. Упрощённая форма их не перезаписывает. " +
                    "Для изменения используйте JSON редактор профиля в обычном режиме VPN.",
                color = ExpertColors.muted, fontSize = 13.sp
            )
            TextButton(onClick = { importedInterfaceDetails(profile) }) { Text("Сохранённая привязка и пути") }
        }
    }
}

@Composable
internal fun ExpertExternalExitEditor(
    original: ExpertExternalProfile?,
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    onDismiss: () -> Unit,
    initialKind: ExternalExitKind = ExternalExitKind.SOCKS5,
) {
    val initial = original?.request
    var kind by remember { mutableStateOf(initial?.kind ?: initialKind) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember {
        mutableStateOf(
            initial?.port?.takeIf { it > 0 }?.toString()
                ?: if (initialKind == ExternalExitKind.HTTP) "8080" else "1080"
        )
    }
    var auth by remember { mutableStateOf(!initial?.username.isNullOrEmpty() || !initial?.password.isNullOrEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var revealPassword by remember { mutableStateOf(false) }
    var tls by remember { mutableStateOf(initial?.tls ?: false) }
    var binding by remember { mutableStateOf(initial?.binding) }
    var dns by remember { mutableStateOf(initial?.dnsServer.orEmpty()) }
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var localFailure by remember { mutableStateOf<String?>(null) }
    val corporate = kind == ExternalExitKind.CORPORATE_INTERFACE
    val editable = pendingToken == null && state.externalSavePending == null
    LaunchedEffect(corporate) {
        if (corporate && state.interfaces.isEmpty() && !state.interfacesLoading) onIntent(ExpertIntent.RefreshInterfaces)
    }
    val request = ExternalExitRequest(
        kind, name.trim(), host.trim(), port.toIntOrNull() ?: 0,
        if (auth && !corporate) username else "", if (auth && !corporate) password else "",
        tls = tls && kind == ExternalExitKind.HTTP, binding = if (corporate) binding else null,
        dnsServer = dns.trim().takeIf { corporate && it.isNotBlank() }
    )
    val selectedInterface = state.interfaces.firstOrNull { sameBinding(it.binding, binding) }
    val retainedBinding = original != null && sameBinding(initial?.binding, binding)
    val errors = ExternalExitProfiles.validate(request) + buildList {
        if (auth && !corporate && username.isEmpty()) add("Для авторизации укажите имя пользователя.")
        if (corporate && selectedInterface?.eligible != true && !retainedBinding) {
            add("Обновите список и выберите доступный подтверждённый интерфейс Windows.")
        }
    }
    LaunchedEffect(pendingToken, state.externalSaveAck, state.externalSaveErrorRequestId) {
        val token = pendingToken ?: return@LaunchedEffect
        if (state.externalSaveErrorRequestId == token) {
            localFailure = state.externalSaveError ?: "Профиль не сохранён. Поля оставлены для исправления и повторной попытки."
            pendingToken = null
        } else if (state.externalSaveAck == token) {
            onDismiss()
        }
    }
    ExpertModal(if (original == null) "Добавить внешний выход" else "Изменить внешний выход", onDismiss, {
        val token = UUID.randomUUID().toString()
        pendingToken = token
        localFailure = null
        onIntent(ExpertIntent.SaveExternalExit(original?.id, request, token, original?.fingerprint))
    }, errors.isEmpty() && editable && !state.busy, "Сохранить профиль") {
        if (pendingToken != null) {
            ExpertMessage(
                "Записываем профиль",
                "Окно закроется после подтверждения. Если закрыть его сейчас, начатая запись продолжится."
            )
        }
        localFailure?.let { ExpertMessage("Запись не завершена", it, error = true) }
        OutlinedTextField(
            name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название профиля") },
            singleLine = true, enabled = editable
        )
        if (original == null && editable) {
            ExpertSelect("Тип выхода", ExternalExitKind.entries.map { it to externalKindName(it) }, kind) {
                if (port in setOf("1080", "8080")) port = if (it == ExternalExitKind.HTTP) "8080" else "1080"
                kind = it
            }
        } else {
            ExpertTag(externalKindName(kind))
            Text(
                "Идентификатор профиля и связи сохраняются. Для другого типа транспорта создайте новый профиль.",
                color = ExpertColors.muted, fontSize = 12.sp
            )
        }
        if (corporate) {
            ExpertMessage(
                "Привязываемся к интерфейсу Windows",
                "Для корпоративного VPN сначала подключите его обычным клиентом. LerNET направит соединения через выбранный адаптер; " +
                    "сам SSTP или другой системный VPN эта форма не запускает. В списке есть и физические адаптеры: " +
                    "выбор Wi-Fi или Ethernet сам по себе не включает VPN или шифрование."
            )
            OutlinedButton(onClick = { onIntent(ExpertIntent.RefreshInterfaces) }, enabled = !state.interfacesLoading && editable) {
                Text(if (state.interfacesLoading) "Читаем интерфейсы…" else "Обновить интерфейсы")
            }
            state.interfacesNotice?.let { Text(it, color = ExpertColors.muted, fontSize = 12.sp) }
            val options = state.interfaces.filter { it.eligible }.map {
                it.binding to "${it.label} · №${it.binding.index} · ${it.status}"
            }.toMutableList()
            initial?.binding?.let { retained ->
                if (options.none {
                        sameBinding(
                            it.first,
                            retained
                        )
                    }
                ) {
                    options.add(retained to "${retained.name} · сохранённая привязка, сейчас недоступна")
                }
            }
            if (editable) {
                ExpertSelect<VerifiedInterfaceBinding?>(
                    "Подтверждённый адаптер",
                    listOf(null to "Выберите адаптер") + options, binding
                ) { binding = it }
            } else {
                Text("Подтверждённый адаптер: ${binding?.name ?: "не выбран"}", color = ExpertColors.muted)
            }
            selectedInterface?.let { selected ->
                selected.reason?.let { ExpertMessage("Что известно об адаптере", it, warning = !selected.eligible) }
            }
            if (binding != null && selectedInterface?.eligible != true) {
                ExpertMessage(
                    "Привязка пока недоступна",
                    "Она сохраняется для другого устройства или повторного подключения. Трафик не будет отправлен через " +
                        "случайный физический адаптер.",
                    warning = true
                )
            }
            OutlinedTextField(
                dns, { dns = it }, Modifier.fillMaxWidth(), label = {
                    Text("DNS корпоративной сети, если нужен")
                }, singleLine = true,
                enabled = editable,
                supportingText = {
                    Text(
                        "IPv4 или IPv6. Пустое поле задаёт 1.1.1.1 через этот выход. " +
                            "Для внутренних имён укажите DNS компании."
                    )
                }
            )
            ExpertMessage(
                "Идентичность проверяется при подключении",
                "Совпасть должны GUID, имя, номер и рабочее состояние адаптера. При смене или отключении адаптера " +
                    "выход станет недоступен. " +
                    "Дальше действует выбранное в ветке правило отказа."
            )
        } else {
            OutlinedTextField(
                host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Сервер или IP") }, singleLine = true,
                enabled = editable,
                supportingText = { Text("Имя без https://, порта и пути. Для локального прокси можно указать 127.0.0.1.") }
            )
            OutlinedTextField(
                port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Порт") }, singleLine = true, enabled = editable,
                isError = port.toIntOrNull()?.let { it in 1..65535 } != true
            )
            if (kind == ExternalExitKind.HTTP && editable) {
                ExpertToggle("TLS к HTTP-прокси", "Проверка сертификата сервера остаётся включённой.", tls) { tls = it }
            }
            if (editable) {
                ExpertToggle(
                    "Нужна авторизация",
                    "Учётные данные сохраняются в локальном профиле и попадают в его явный экспорт.", auth
                ) { auth = it }
            }
            if (auth) {
                OutlinedTextField(
                    username, { username = it }, Modifier.fillMaxWidth(), label = {
                        Text("Имя пользователя")
                    }, singleLine = true,
                    enabled = editable
                )
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") },
                    singleLine = true, enabled = editable,
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealPassword = !revealPassword }) {
                            Text(if (revealPassword) "Скрыть" else "Показать")
                        }
                    }
                )
            }
            ExpertMessage(
                "Прокси — отдельный путь",
                "Протокол прокси сам по себе не гарантирует шифрование. Выбирайте доверенный сервер. " +
                    "Этот профиль можно использовать в нескольких ветках, папке или общем канале."
            )
        }
        if (errors.isNotEmpty()) ExpertMessage("Проверьте поля", errors.joinToString("\n"), error = true)
    }
}

@Composable
internal fun ExpertExternalExitDetails(
    profile: ExpertExternalProfile,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    openScope: (PolicyScope) -> Unit = {},
) {
    ExpertModal(profile.request.name, onDismiss, onDismiss, true, "Закрыть") {
        ExpertTag(externalKindName(profile.request.kind))
        val request = profile.request
        if (request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
            Text("Адаптер: ${request.binding?.name ?: "не передан"}", color = ExpertColors.text)
            Text("Номер: ${request.binding?.index ?: "не передан"}", color = ExpertColors.muted)
            Text("GUID: ${request.binding?.guid ?: "не передан"}", color = ExpertColors.muted, fontSize = 12.sp)
            Text("DNS: ${request.dnsServer ?: "сохранённая настройка профиля"}", color = ExpertColors.muted)
            ExpertMessage(
                "Привязка сохраняется между устройствами",
                "На другом Windows-устройстве выберите его подтверждённый адаптер. На Android эта привязка не создаёт системный VPN " +
                    "и остаётся недоступной; ветка не получает обычный прямой выход автоматически."
            )
        } else {
            Text("Сервер: ${externalEndpoint(request)}", color = ExpertColors.text)
            Text(
                if (request.username.isEmpty()) {
                    "Авторизация не используется"
                } else {
                    "Имя пользователя: ${request.username}"
                },
                color = ExpertColors.muted
            )
            if (request.password.isNotEmpty()) Text("Пароль сохранён; посмотреть его можно в редакторе.", color = ExpertColors.muted)
            if (request.kind == ExternalExitKind.HTTP) {
                Text(
                    if (
                        request.tls
                    ) {
                        "TLS к прокси включён"
                    } else {
                        "TLS к прокси выключен"
                    },
                    color = ExpertColors.muted
                )
            }
        }
        ExternalReferenceLinks(profile.id, state, openScope)
        OutlinedButton(onClick = onEdit, enabled = !state.busy) { Text("Изменить профиль") }
    }
}

@Composable
internal fun ExpertImportedInterfaceDetails(
    profile: ExpertProfile,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    openScope: (PolicyScope) -> Unit = {},
) {
    val binding = profile.interfaceBinding ?: return
    ExpertModal(profile.name, onDismiss, onDismiss, true, "Закрыть") {
        ExpertTag("Расширенный профиль · только просмотр", ExpertColors.muted)
        Text("Сохранённый адаптер: ${binding.name}", color = ExpertColors.text)
        Text("Номер: ${binding.index}", color = ExpertColors.muted)
        Text("GUID: ${binding.guid}", color = ExpertColors.muted, fontSize = 12.sp)
        ExpertMessage(
            "Это привязка из профиля",
            "GUID, имя и номер указывают нужный интерфейс Windows. Они сами по себе не подтверждают, " +
                "что VPN сейчас подключён или что адаптер шифрует трафик. Ядро проверяет идентичность и доступность перед соединением."
        )
        val current = state.interfaces.firstOrNull { sameBinding(it.binding, binding) }
        ExpertMessage(
            "Сведения текущего снимка",
            current?.let {
                "${it.label} · ${it.status}${it.reason?.let { reason -> ". $reason" }.orEmpty()}"
            } ?: "Список адаптеров ещё не подтвердил эту привязку на текущем устройстве.",
            warning = current?.eligible != true
        )
        ExpertMessage(
            "Расширенные параметры сохраняются",
            "Здесь показаны только привязка и связи со схемой. Остальные поля профиля остаются как в импорте. " +
                "Откройте профиль в обычном режиме VPN и используйте его JSON редактор для изменения; " +
                "упрощённая форма не может безопасно представить все его настройки."
        )
        ExternalReferenceLinks(profile.id, state, openScope)
    }
}

@Composable
private fun ExternalReferenceLinks(profileId: String, state: ExpertUiState, openScope: (PolicyScope) -> Unit) {
    val references = externalProfileReferences(state, profileId)
    Text("Пути черновика с этой целью", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
    if (references.isEmpty()) {
        Text(
            "Прямых ссылок пока нет. Профиль также может использоваться через папку или канал.", color = ExpertColors.muted
        )
    }
    references.forEach { reference ->
        TextButton(onClick = { openScope(reference.scope) }) {
            Text("${ExpertPolicyEditing.scopeName(reference.scope, state)} → ${reference.title}")
        }
    }
}

private fun sameBinding(first: VerifiedInterfaceBinding?, second: VerifiedInterfaceBinding?): Boolean =
    first != null && second != null && ExternalExitProfiles.normalizeBinding(first) == ExternalExitProfiles.normalizeBinding(second)
