package app.lernet.engine.net.observation

/** Each trigger includes its exact observation, potential effect and a non-destructive next step. */
object ObservationFindingGuide {
    fun explain(snapshot: NetworkSnapshot, finding: NetworkFinding): NetworkFinding {
        if (finding.reason.isNotBlank()) return finding
        fun rows(vararg ids: String) = snapshot.sources.filter { it.id in ids }.flatMap { it.rows }
        fun adapterRow(adapter: ObservedAdapter) = EvidenceRow(adapter.id, adapter.name, linkedMapOf(
            "Описание" to adapter.description.ifBlank { "Описание не передано" },
            "Состояние" to if (adapter.up) "Интерфейс включён" else "Интерфейс отключён",
            "Номер интерфейса" to adapter.index.toString(),
            "Адреса устройства" to adapter.addresses.joinToString().ifBlank { "Не переданы" }))
        return when (finding.code) {
            "inactive-route" -> {
                val routes = snapshot.routes.filter { route -> snapshot.adapters.any {
                    it.id == route.adapterId && !it.up && !ObservationGuide.isServiceRoute(route, it)
                } }
                finding.copy(reason = "Мы сопоставили номер/идентификатор интерфейса у маршрута со списком адаптеров. У перечисленных ниже адаптеров состояние не Up. Обычные локальные, multicast и broadcast-записи не включены в это предупреждение.",
                    impact = "Если нужный адрес использовал только этот интерфейс, после отключения он может стать недоступен. Другой подходящий активный маршрут может продолжить работать; сами записи не доказывают сбой.",
                    nextSteps = listOf("Посмотрите название адаптера ниже: это кабель, Wi-Fi или виртуальный интерфейс?", "Откройте «Маршруты» и введите IP проблемного ресурса. Проверьте, какой доступный путь найден.", "Сравните снимки до и после подключения. Не удаляйте записи только из-за этого предупреждения."),
                    relatedItems = routes.groupBy { it.adapterId }.map { (id, entries) ->
                        val adapter = snapshot.adapters.first { it.id == id }
                        adapterRow(adapter).copy(fields = adapterRow(adapter).fields + mapOf(
                            "Маршруты" to entries.joinToString("\n") { "${it.prefix} → ${it.nextHop} · ${if (it.store == "PersistentStore") "сохранённый" else "текущий"}" },
                            "Почему отмечен" to "Маршрут связан с этим отключённым интерфейсом. Какой путь реально выберет приложение, здесь не проверялось."))
                    })
            }
            "proxy-port-occupied" -> finding.copy(
                reason = "Найден слушающий TCP-порт 2080 на loopback или всех локальных адресах. PID не входит в известные процессы текущего экземпляра LerNET.",
                impact = "Локальный прокси может не открыть тот же адрес и порт. IPv4/IPv6 и параметры сокета могут различаться, поэтому это возможный конфликт, а не подтверждённый отказ.",
                nextSteps = listOf("Сверьте адрес, порт и владельца в «Открытых TCP-портах».", "Проверьте, не запущена ли вторая копия прокси или LerNET. Завершайте программу только после проверки её роли."),
                relatedItems = snapshot.listeners.filter { it.port == 2080 && "${it.address}:${it.port} · ${it.process.ifBlank { "PID ${it.pid}" }}" in finding.evidence }.map {
                    EvidenceRow("listener:${it.address}:${it.pid}", it.process.ifBlank { "Процесс ${it.pid}" }, mapOf("Адрес и порт" to "${it.address}:${it.port}", "PID" to it.pid.toString())) })
            "loopback-proxy-no-listener" -> finding.copy(
                reason = "Включённый ручной прокси пользователя указывает на localhost/loopback. В успешно прочитанном списке слушающих портов подходящий TCP-сервис не найден.",
                impact = "Приложения, использующие этот прокси, могут отправлять запросы в неработающий посредник. Другие приложения могут работать напрямую; между чтением источников процесс мог запуститься или остановиться.",
                nextSteps = listOf("Сверьте адрес прокси с портами работающей программы.", "Обновите снимок после запуска нужного клиента. Не меняйте прокси, пока не установлено, какое приложение должно его обслуживать."), relatedItems = rows("user-proxy"))
            "windows-block-event" -> finding.copy(
                reason = "В доступном журнале найден Event ID 5157 от Microsoft-Windows-Security-Auditing: Windows записала блокировку соединения.",
                impact = "В указанный момент конкретный запрос был заблокирован. Это не означает, что все запросы программы заблокированы сейчас, и не назначает виновником любой найденный VPN.",
                nextSteps = listOf("Сопоставьте время, программу и адрес со своим сбоем.", "Сравните FilterRTID с фильтрами WFP этого запуска Windows. Для окончательного вывода нужны совпадающие условия."),
                relatedItems = rows("events", "trace-events").filter { it.fields["Event ID"] == "5157" && it.fields["Источник"] == "Microsoft-Windows-Security-Auditing" }.take(8))
            "split-default" -> finding.copy(
                reason = "В текущей таблице найдены префиксы /1. Подходящий /1 точнее общего /0 в том же сетевом контексте; это один из способов направлять трафик через VPN.",
                impact = "Показанный обычный шлюз может оставаться в таблице, хотя часть адресов пойдёт через другой интерфейс. По /1 нельзя установить владельца VPN или подтвердить передачу пакетов.",
                nextSteps = listOf("В разделе «Маршруты» рассчитайте путь к нужному IP.", "Сверьте интерфейс выбранного маршрута с адаптерами и известным клиентом VPN."),
                relatedItems = snapshot.routes.filter { it.store.equals("ActiveStore", true) && it.prefix in setOf("0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1") }.map { route ->
                    EvidenceRow("route:${route.adapterId}:${route.prefix}", snapshot.adapters.firstOrNull { it.id == route.adapterId }?.name ?: "Интерфейс ${route.interfaceIndex}", mapOf("Назначение" to route.prefix, "Следующий узел" to route.nextHop, "Номер интерфейса" to route.interfaceIndex.toString())) })
            "virtual-active" -> finding.copy(
                reason = if (snapshot.platform.startsWith("Android", true)) "Android пометил эти видимые сети как VPN. Это факт о доступных приложению свойствах, а не подозрение на блокировку." else "В списке ОС эти адаптеры помечены программными или неаппаратными и включёнными. Это факт об интерфейсах, а не подозрение на блокировку.",
                impact = if (snapshot.platform.startsWith("Android", true)) "VPN виден в этой области Android. Сеть по умолчанию, привязки приложений и проверка доступа уточняют его роль; работа интернета через него не подтверждена." else "Они могут обслуживать VPN, виртуальные машины или штатные функции Windows. WAN Miniport часто присутствует без активного VPN; для передачи нужен подходящий маршрут.",
                nextSteps = listOf("Смотрите описание каждого адаптера, затем связанные маршруты.", "Не отключайте штатный компонент только потому, что он виртуальный."),
                relatedItems = snapshot.adapters.filter { it.virtual && it.up }.map(::adapterRow))
            "partial-snapshot" -> finding.copy(
                reason = "Один или несколько источников не прочитаны целиком: ограничение ОС, отказ доступа, ошибка, таймаут или ограниченная выборка. Причина каждого источника показана отдельно.",
                impact = "Эти пробелы ограничивают выводы. Например, без WFP нельзя исключить скрытый фильтр. Отсутствие Hyper-V или компонентов NAT может быть ожидаемым и не указывает на сломанную сеть.",
                nextSteps = listOf("Посмотрите причину у каждого источника, а не общий цвет предупреждения.", "Для ошибки чтения сохраните отчёт и обновите снимок. Для ограничений Android или отсутствующего компонента повышение прав может ничего не изменить."),
                relatedItems = snapshot.sources.filter { it.id in finding.sourceIds }.map { source -> EvidenceRow(source.id, source.title, linkedMapOf(
                    "Что произошло" to ObservationGuide.availability(source),
                    "Причина источника" to source.detail.ifBlank { if (!source.complete) "Выборка ограничена" else source.state.name },
                    "Что это ограничивает" to ObservationGuide.source(source.id).limits)) })
            "endpoint-route" -> finding.copy(
                reason = "Числовой адрес выбранного сервера сопоставлен с текущими маршрутами основного сетевого контекста. Отключённые известные интерфейсы исключены; среди кандидатов учтены префиксы и известные метрики.",
                impact = "Это ожидаемый путь до сервера по таблице, а не измерение доступности или путь всего трафика внутри VPN. DNS, HTTP и передачу пакетов этот расчёт не выполняет.",
                nextSteps = listOf("Сверьте указанный интерфейс с ожидаемой сетью.", "Проверьте связь через профиль отдельно. Для другого ресурса рассчитайте его IP в «Маршрутах»."))
            "endpoint-other-virtual" -> finding.copy(
                reason = "Среди подходящих маршрутов к серверу есть включённый программный интерфейс, имя которого не совпадает с LerNET. Его владелец по имени не установлен.",
                impact = "Связь с сервером может зависеть от другого туннеля, виртуальной машины или сетевого компонента. Это зависимость пути, а не доказательство конфликта двух VPN.",
                nextSteps = listOf("Сопоставьте имя и описание интерфейса с установленными программами и маршрутами.", "Сравните снимки после изменения подключения только когда безопасно прервать этот путь."))
            "endpoint-hostname" -> finding.copy(
                reason = "Адрес выбранного сервера записан именем, а не числовым IPv4/IPv6. Пассивный сбор не выполняет DNS-запрос для его разрешения.",
                impact = "Таблица маршрутов применяется к IP, поэтому ожидаемый путь этого имени пока не рассчитан. Это не ошибка профиля и не означает, что сервер недоступен.",
                nextSteps = listOf("Если IP сервера уже известен, введите его в расчёт маршрута.", "Для проверки самого подключения используйте отдельную проверку связи профиля."))
            "endpoint-route-unavailable" -> finding.copy(
                reason = "Расчёт по доступным маршрутам не получил надёжного результата. В основании ниже указана конкретная причина: недоступная таблица, неизвестные метрики или отсутствие подходящего пути.",
                impact = "Пассивная аналитика не может объяснить выбранный путь. Из этого нельзя заключить, что интернет или профиль обязательно не работают.",
                nextSteps = listOf("Проверьте доступность источников маршрутов и интерфейсов.", "Обновите снимок после стабилизации сети и повторите расчёт для числового адреса."))
            "android-default" -> finding.copy(reason = "Android сообщил текущую сеть по умолчанию для процесса LerNET на момент чтения.",
                impact = "Системные и другие приложения могут быть привязаны к иной сети. Этот факт нельзя переносить на всё устройство.",
                nextSteps = listOf("Сверьте видимый интерфейс с Wi-Fi, мобильной сетью или VPN.", "Обновите снимок после смены подключения."), relatedItems = rows("adapters").filter { it.title in finding.evidence })
            "android-unvalidated" -> finding.copy(reason = "У сети по умолчанию не обнаружен флаг NET_CAPABILITY_VALIDATED при доступных свойствах. Android не подтвердил общий доступ своей системной проверкой.",
                impact = "Это может происходить при входе через страницу Wi-Fi, смене сети или недоступности адреса системной проверки. Нужный ресурс при этом может работать; мёртвый VPN этим не доказан.",
                nextSteps = listOf("Проверьте, не нужен ли вход в сеть Wi-Fi.", "Проверьте нужный ресурс и повторите снимок после стабилизации сети."))
            else -> finding.copy(reason = finding.explanation, impact = "Вывод ограничен указанными источниками и временем снимка. Он не устанавливает причину сбоя без проверки нужного соединения.",
                nextSteps = listOf("Сверьте основания с источниками ниже и сравните два снимка после воспроизведения проблемы."))
        }
    }
}
