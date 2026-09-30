package app.lernet.desktop.observation

import app.lernet.engine.net.observation.*

internal data class ClientObservationContext(val profileName: String = "", val endpoint: String = "", val protocol: String = "", val requestedMode: String = "FULL_VPN")

/** Configuration metadata only; never serialize the original outbound or its credentials. */
internal fun withClientContext(snapshot: NetworkSnapshot, context: ClientObservationContext): NetworkSnapshot {
    val source = ObservationSource("lernet-context", "Выбранный профиль LerNET",
        "Параметры выбранного профиля на начало снимка. Ключи доступа не читаются в отчёт. Проверка маршрута выполняется только для числового IP без DNS.",
        rows = listOf(EvidenceRow("selected-profile", context.profileName.ifBlank { "Профиль не выбран" }, mapOf(
            "Профиль" to context.profileName, "Сервер" to context.endpoint, "Протокол" to context.protocol,
            "Запрошенный режим" to context.requestedMode,
        ))), capturedAt = snapshot.startedAt)
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
                    FindingKind.POTENTIAL_CONFLICT, listOf(source.id, "routes", "adapters"), externalVirtual.map { it.name + " · " + it.description }))
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
