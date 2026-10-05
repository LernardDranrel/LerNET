package app.lernet.desktop.observation

import app.lernet.engine.net.observation.*

internal data class ClientObservationContext(val profileName: String = "", val endpoint: String = "", val protocol: String = "", val requestedMode: String = "FULL_VPN",
    val tunnelState: String = "", val tunnelMessage: String = "", val clientVersion: String = "")

/** Configuration metadata only; never serialize the original outbound or its credentials. */
internal fun withClientContext(snapshot: NetworkSnapshot, context: ClientObservationContext): NetworkSnapshot {
    val source = ObservationSource("lernet-context", "Выбранный профиль LerNET",
        "Параметры выбранного профиля на начало снимка. Ключи доступа не читаются в отчёт. Проверка маршрута выполняется только для числового IP без DNS.",
        rows = listOf(EvidenceRow("selected-profile", context.profileName.ifBlank { "Профиль не выбран" }, mapOf(
            "Профиль" to context.profileName, "Сервер" to context.endpoint, "Протокол" to context.protocol,
            "Запрошенный режим" to context.requestedMode,
        ) + mapOf("Версия клиента" to context.clientVersion, "Состояние ядра" to context.tunnelState,
            "Статус ядра" to app.lernet.desktop.ProbeDiagnostics.clean(context.tunnelMessage)).filterValues(String::isNotBlank))), capturedAt = snapshot.startedAt)
    val additional = buildList {
        if (context.requestedMode == "FULL_VPN" && !snapshot.elevated) add(NetworkFinding("admin-required",
            "Для полного VPN нужны права администратора", "LerNET сейчас может работать как прокси. Для создания TUN Windows требует повышенные права.",
            FindingKind.POTENTIAL_CONFLICT, listOf(source.id), listOf("Запрошен полный VPN · снимок прочитан без повышения прав")))
        if (context.endpoint.isNotBlank()) {
            val selection = NetworkRouteSelection.select(snapshot, context.endpoint)
            if (selection.error.isEmpty()) {
                val adapters = selection.candidates.mapNotNull { candidate -> snapshot.adapters.firstOrNull { it.id == candidate.adapterId } }.distinctBy { it.id }
                val externalVirtual = adapters.filter { it.virtual && !it.name.equals("LerNET", true) && !it.name.startsWith("LerNET ", true) }
                add(NetworkFinding("endpoint-route", "Путь к выбранному серверу по снимку",
                    "Этот расчёт объясняет таблицу маршрутов основного сетевого контекста. Он не отправляет пакеты и не подтверждает доступность сервера; равнозначные пути могут выбираться системой иначе.",
                    FindingKind.FACT, listOf(source.id, "routes", "interfaces", "adapters"), selection.candidates.map { route ->
                        "${context.endpoint} · ${route.prefix} → ${route.nextHop} · ${adapters.firstOrNull { it.id == route.adapterId }?.name ?: "интерфейс ${route.interfaceIndex}"}"
                    }))
                if (externalVirtual.isNotEmpty()) add(NetworkFinding("endpoint-other-virtual", "Путь к серверу зависит от виртуального канала",
                    "Подходящий маршрут ведёт через другой виртуальный адаптер. Это может быть полезная корпоративная сеть или второй VPN. Проверьте этот путь, если LerNET подключается, а интернет остаётся у другого клиента.",
                    FindingKind.POTENTIAL_CONFLICT, listOf(source.id, "routes", "adapters", "processes", "services"), externalVirtual.map { it.name + " · " + it.description },
                    reason = "В таблице маршрутов путь к ${context.endpoint} проходит через внешний виртуальный интерфейс: ${externalVirtual.joinToString { it.name }}. Связанные ниже программы найдены по совпадению названия; это подсказка, а не доказательство владения адаптером.",
                    impact = "Предварительная проверка LerNET без собственного TUN использует текущий путь Windows. При отключении другого VPN этот путь может измениться или перестать работать. Это ещё не подтверждает причину ошибки проверки.",
                    nextSteps = listOf("Сохраните полный разбор перед отключением другого клиента.", "Когда безопасно прервать сеть, штатно отключите другой VPN и обновите снимок в том же окне.", "В «Что изменилось» сравните адаптер, маршруты, DNS и фильтры. Сохраните полный разбор ещё раз."),
                    relatedItems = externalVirtual.flatMap { adapter -> listOf(EvidenceRow(adapter.id, adapter.name, mapOf("Описание" to adapter.description, "Интерфейс" to adapter.index.toString()))) + NetworkDependencies.relatedPrograms(snapshot, adapter) }))
            } else if (!NetworkRouteSelection.isNumericAddress(context.endpoint)) {
                add(NetworkFinding("endpoint-hostname", "Сервер задан именем",
                    "Пассивный снимок не выполняет DNS-запрос. Чтобы изучить путь, в разделе «Маршруты» укажите уже известный IP сервера.",
                    FindingKind.INSUFFICIENT_DATA, listOf(source.id), listOf(context.endpoint)))
            } else {
                add(NetworkFinding("endpoint-route-unavailable", "Путь к серверу пока не определён",
                    selection.error, FindingKind.INSUFFICIENT_DATA, listOf(source.id, "routes", "interfaces"), listOf(context.endpoint)))
            }
        }
    }
    val withContext = snapshot.copy(sources = snapshot.sources + source)
    return withContext.copy(findings = (snapshot.findings + additional).map { ObservationFindingGuide.explain(withContext, it) })
}
