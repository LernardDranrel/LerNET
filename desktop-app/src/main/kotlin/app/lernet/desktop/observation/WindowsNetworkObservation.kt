package app.lernet.desktop.observation

import app.lernet.desktop.WindowsElevation
import app.lernet.engine.net.observation.*
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal data class WindowsObservationDefinition(val id: String, val title: String, val explanation: String)

internal class WindowsNetworkObservation(private val runner: ObservationCommandRunner = SystemObservationCommandRunner) {
    fun collect(): NetworkSnapshot {
        val started = System.currentTimeMillis()
        if (!System.getProperty("os.name").startsWith("Windows")) return NetworkSnapshot(
            platform = System.getProperty("os.name"), startedAt = started,
            sources = definitions.map { source(it, SourceState.UNSUPPORTED, detail = "Источник доступен в Windows") },
        )
        val script = javaClass.getResourceAsStream("/observation.ps1")?.use { it.readBytes() }
            ?: error("Не найден сценарий чтения Windows")
        val scriptPath = Files.createTempFile("lernet-observation-", ".ps1")
        val wfpPath = Files.createTempFile("lernet-wfp-state-", ".xml")
        val pool = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "LerNET-observation-source").apply { isDaemon = true } }
        try {
            Files.write(scriptPath, script)
            val futures = definitions.map { definition ->
                pool.submit<ObservationSource> {
                    runCatching {
                        val result = runner.run(listOf(windowsTool("WindowsPowerShell/v1.0/powershell.exe"),
                            "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString(),
                            "-Source", definition.id, "-WfpStateFile", wfpPath.toString()), 15_000)
                        parseSource(definition, result)
                    }.getOrElse { source(definition, SourceState.ERROR, detail = it.message ?: it.javaClass.simpleName) }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65)
            val sources = futures.mapIndexed { index, future ->
                runCatching { future.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrElse {
                    future.cancel(true)
                    if (it is InterruptedException) throw it
                    source(definitions[index], SourceState.TIMEOUT, detail = "Источник не завершился за время снимка")
                }
            }.toMutableList()
            sources += WindowsObservationEvents(runner).read()
            val snapshot = assemble(sources, started, WindowsElevation.isElevated)
            val ownPid = ProcessHandle.current().pid()
            val descendants = ProcessHandle.current().descendants().use { it.map { process -> process.pid() }.toList().toSet() }
            return snapshot.copy(findings = NetworkObservationAnalysis.analyze(snapshot, descendants + ownPid))
        } finally {
            pool.shutdownNow()
            try { pool.awaitTermination(3, TimeUnit.SECONDS) } finally {
                try { Files.deleteIfExists(scriptPath) } finally { Files.deleteIfExists(wfpPath) }
            }
        }
    }

    companion object {
        internal val definitions = listOf(
            WindowsObservationDefinition("adapters", "Адаптеры", "Физические и виртуальные входы в сеть. Включённый адаптер не доказывает, что через него идёт трафик."),
            WindowsObservationDefinition("addresses", "Адреса устройства", "Локальные IPv4 и IPv6. Это адреса интерфейсов, а не обязательно ваш адрес в интернете."),
            WindowsObservationDefinition("interfaces", "Параметры IP", "Метрики, MTU, DHCP и состояние интерфейсов отдельно для IPv4 и IPv6."),
            WindowsObservationDefinition("routes", "Текущие маршруты", "Windows сначала выбирает самый точный префикс, затем метрики. Это снимок таблицы, не проверка доступности."),
            WindowsObservationDefinition("persistent-routes", "Сохранённые маршруты", "Записи, которые могут пережить перезапуск. Наличие записи не означает, что Windows сейчас её использует."),
            WindowsObservationDefinition("dns", "DNS-серверы", "Кто переводит имена сайтов в IP на каждом интерфейсе. Здесь только настройки; запросы не отправляются."),
            WindowsObservationDefinition("dns-policy", "Правила DNS", "Эффективная политика NRPT может направлять имена компании к отдельным серверам."),
            WindowsObservationDefinition("dns-suffix", "Суффиксы DNS", "Дополнения к коротким именам компьютеров и параметры регистрации адресов."),
            WindowsObservationDefinition("hosts", "Локальные имена", "Записи файла hosts могут менять адрес сайта до обращения к DNS. Комментарии исключены."),
            WindowsObservationDefinition("user-proxy", "Прокси пользователя", "Сохранённые значения реестра текущего пользователя. Это не полный расчёт прокси активного соединения и WPAD; PAC не загружается. SID показывает, чьи значения прочитаны."),
            WindowsObservationDefinition("environment-proxy", "Прокси окружения", "Переменные прокси текущего процесса. Учётные данные и параметры URL скрыты; окружение других пользователей не читается."),
            WindowsObservationDefinition("installed-apps", "Установленные программы", "Записи установки Windows. Программа в этом списке не обязательно запущена или управляет сетью; переносимые приложения могут отсутствовать."),
            WindowsObservationDefinition("winhttp-proxy", "Прокси WinHTTP", "Базовые настройки WinHTTP для программ, использующих этот API. Расширенные настройки, PAC и WPAD здесь не вычисляются; настройки браузера могут отличаться."),
            WindowsObservationDefinition("listeners", "Открытые TCP-порты", "Программы, принимающие подключения. Это помогает обнаружить занятый порт локального прокси."),
            WindowsObservationDefinition("connections", "TCP-соединения", "Адреса и состояния текущих соединений. Содержимое трафика и командные строки не читаются."),
            WindowsObservationDefinition("udp-endpoints", "UDP-порты", "Локальные UDP-порты и PID владельца. Эта таблица не сообщает адрес удалённого получателя."),
            WindowsObservationDefinition("processes", "Программы и родители", "PID, родительский PID и исполняемый файл. Совпадение имени с VPN не доказывает владение адаптером."),
            WindowsObservationDefinition("vpn-user", "VPN пользователя Windows", "Профили встроенного VPN: сервер, протокол, состояние, разделение трафика. Сторонние клиенты могут отсутствовать."),
            WindowsObservationDefinition("vpn-global", "Общие VPN Windows", "Системная телефонная книга VPN. Пароли, ключи и полные конфигурации не читаются."),
            WindowsObservationDefinition("network-profiles", "Сети и категории", "Как Windows описывает подключённые сети. Её статус доступа не заменяет проверку нужного ресурса."),
            WindowsObservationDefinition("services", "Работающие службы", "Службы и их PID помогают связать сетевой процесс с компонентом системы. Пути с аргументами не читаются."),
            WindowsObservationDefinition("bindings", "Компоненты адаптеров", "Привязки протоколов и фильтров к адаптерам. Включённый фильтр не обязательно блокирует трафик."),
            WindowsObservationDefinition("drivers", "Сетевые драйверы", "Версии, поставщики и цифровая подпись сетевых устройств. Подпись сама по себе не говорит о качестве драйвера."),
            WindowsObservationDefinition("adapter-properties", "Настройки драйверов", "Дополнительные параметры, которые драйвер публикует Windows: например Jumbo, RSS или энергосбережение."),
            WindowsObservationDefinition("adapter-counters", "Счётчики адаптеров", "Байты, ошибки и отброшенные пакеты с начала работы счётчика. Это не скорость и не результат текущей проверки."),
            WindowsObservationDefinition("neighbors", "Соседи в локальной сети", "Уже известный системе ARP/ND-кэш. LerNET не сканирует сеть и не ищет новые устройства."),
            WindowsObservationDefinition("compartments", "Изолированные сети", "Сетевые контексты Windows, контейнеров и виртуальных машин могут иметь отдельные таблицы маршрутов."),
            WindowsObservationDefinition("firewall-profiles", "Профили брандмауэра", "Эффективные профили и действия по умолчанию. Правила не изменяются."),
            WindowsObservationDefinition("firewall-rules", "Правила брандмауэра", "Активные правила с приложением, адресами, портами и источником политики. Совпадение одного поля ещё не доказывает блокировку."),
            WindowsObservationDefinition("ipsec", "Активные IPsec-каналы", "Текущие защищённые связи и параметры соединения, без ключей. SSTP и другие VPN не обязаны появляться здесь."),
            WindowsObservationDefinition("nat", "Преобразование адресов", "Локальные NAT-сети Windows. NAT домашнего роутера отсюда не виден."),
            WindowsObservationDefinition("nat-mappings", "Переадресация портов", "Статические правила Windows NAT с внутренними и внешними адресами."),
            WindowsObservationDefinition("hyperv-switch", "Виртуальные коммутаторы", "Hyper-V и подключение к физическим адаптерам. Без установленного модуля этот источник недоступен."),
            WindowsObservationDefinition("winsock", "Сетевые провайдеры Winsock", "Каталог локальных поставщиков сетевых API. Каталог только читается, сброс не выполняется."),
            WindowsObservationDefinition("wlan", "Wi-Fi", "Текущее состояние WLAN. Windows может скрыть детали без разрешения на местоположение."),
            WindowsObservationDefinition("wfp", "Фильтры Windows WFP", "Фильтры, действия, условия и опубликованный поставщик. Runtime ID действуют в текущем запуске Windows; неизвестный callout не расшифровывается."),
        )

        private fun source(d: WindowsObservationDefinition, state: SourceState, rows: List<EvidenceRow> = emptyList(), detail: String = "") =
            ObservationSource(d.id, d.title, d.explanation, state, rows, detail)

        internal fun parseSource(definition: WindowsObservationDefinition, result: ObservationCommandResult): ObservationSource {
            if (result.timedOut) return source(definition, SourceState.TIMEOUT, detail = "Чтение превысило 15 секунд")
            if (result.exitCode != 0) return source(definition, SourceState.ERROR, detail = result.output.take(1200).ifBlank { "Код завершения ${result.exitCode}" })
            val value = Json.parseToJsonElement(result.output).jsonObject
            val state = runCatching { SourceState.valueOf(value["state"]?.jsonPrimitive?.content ?: "ERROR") }.getOrDefault(SourceState.ERROR)
            val rows = (value["rows"] as? JsonArray).orEmpty().mapIndexed { index, item ->
                val fields = item.jsonObject.mapValues { (_, field) -> when (field) {
                    JsonNull -> ""; is JsonPrimitive -> field.content
                    is JsonArray -> field.joinToString(", ") { if (it is JsonPrimitive) it.content else it.toString() }
                    else -> field.toString()
                } }.filterValues { it.isNotBlank() }.mapValues { (_, field) -> field.take(16_384) }
                val key = stableId(definition.id, fields, index)
                val title = listOf("DisplayName", "Name", "DeviceName", "InterfaceAlias", "DestinationPrefix", "LocalAddress", "Namespace", "Entry", "FilterId")
                    .firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) } ?: definition.title
                EvidenceRow(key, title, fields)
            }
            return source(definition, if (state == SourceState.AVAILABLE && rows.isEmpty()) SourceState.EMPTY else state,
                rows, value["detail"]?.jsonPrimitive?.content.orEmpty()).copy(complete = value["complete"]?.jsonPrimitive?.booleanOrNull ?: true)
        }

        private fun stableId(source: String, fields: Map<String, String>, index: Int): String {
            val keys = when (source) {
                "adapters" -> listOf("InterfaceGuid")
                "addresses" -> listOf("InterfaceIndex", "IPAddress", "CompartmentId")
                "interfaces", "dns" -> listOf("InterfaceIndex", "AddressFamily", "CompartmentId")
                "routes", "persistent-routes" -> listOf("DestinationPrefix", "NextHop", "InterfaceIndex", "CompartmentId", "PolicyStore")
                "processes" -> listOf("ProcessId")
                "connections" -> listOf("LocalAddress", "LocalPort", "RemoteAddress", "RemotePort", "OwningProcess")
                "listeners", "udp-endpoints" -> listOf("LocalAddress", "LocalPort", "OwningProcess")
                "drivers" -> listOf("DeviceID")
                "bindings" -> listOf("Name", "ComponentID")
                "adapter-properties" -> listOf("Name", "RegistryKeyword")
                "neighbors" -> listOf("InterfaceIndex", "IPAddress", "CompartmentId")
                "wfp" -> listOf("FilterKey")
                "compartments" -> listOf("CompartmentId")
                "nat-mappings" -> listOf("NatName", "Protocol", "ExternalIPAddress", "ExternalPort", "InternalIPAddress", "InternalPort")
                "installed-apps" -> listOf("PSPath")
                "ipsec" -> listOf("Name", "LocalEndpoint", "RemoteEndpoint", "LocalPort", "RemotePort", "Direction", "IpProtocol")
                else -> listOf("Name", "InterfaceIndex", "Namespace", "Entry", "Configuration", "Catalog", "Interfaces", "UserSID")
            }
            return source + ":" + keys.mapNotNull { fields[it]?.takeIf(String::isNotBlank) }.joinToString("|").ifBlank { index.toString() }
        }

        internal fun assemble(sources: List<ObservationSource>, started: Long, elevated: Boolean): NetworkSnapshot {
            fun rows(id: String) = sources.firstOrNull { it.id == id }?.rows.orEmpty()
            fun EvidenceRow.f(key: String) = fields[key].orEmpty()
            fun EvidenceRow.n(key: String) = f(key).toIntOrNull() ?: 0
            val interfaces = rows("interfaces")
            val adapters = rows("adapters").map { row ->
                val index = row.n("ifIndex")
                val ip = interfaces.firstOrNull { it.n("InterfaceIndex") == index && it.f("AddressFamily") in setOf("2", "IPv4") }
                ObservedAdapter(row.f("InterfaceGuid").ifBlank { "interface:$index" }, index, row.f("Name"), row.f("InterfaceDescription"),
                    row.f("Status").equals("Up", true), row.f("Virtual").equals("true", true) || row.f("HardwareInterface").equals("false", true),
                    rows("addresses").filter { it.n("InterfaceIndex") == index }.map { it.f("IPAddress") + "/" + it.f("PrefixLength") },
                    rows("dns").filter { it.n("InterfaceIndex") == index }.flatMap { it.f("ServerAddresses").split(", ").filter(String::isNotBlank) },
                    ip?.n("InterfaceMetric") ?: 0, ip?.f("NlMtu")?.toIntOrNull())
            }
            val routes = (rows("routes") + rows("persistent-routes")).map { row ->
                val index = row.n("InterfaceIndex")
                val family = if (':' in row.f("DestinationPrefix")) setOf("23", "IPv6") else setOf("2", "IPv4")
                val ip = interfaces.firstOrNull { it.n("InterfaceIndex") == index && it.f("AddressFamily") in family && it.f("CompartmentId") == row.f("CompartmentId") }
                ObservedRoute(row.f("DestinationPrefix"), row.f("NextHop"), adapters.firstOrNull { it.index == index }?.id ?: "interface:$index", index,
                    row.n("RouteMetric"), ip?.n("InterfaceMetric") ?: 0, row.f("PolicyStore"), row.f("CompartmentId"), metricsKnown = row.f("RouteMetric").toIntOrNull() != null && ip?.f("InterfaceMetric")?.toIntOrNull() != null)
            }
            val processById = rows("processes").associateBy { it.f("ProcessId") }
            val listeners = rows("listeners").map { row -> ObservedListener(row.f("LocalAddress"), row.n("LocalPort"),
                row.f("OwningProcess").toLongOrNull() ?: -1, processById[row.f("OwningProcess")]?.f("Name").orEmpty()) }
            val stableSources = sources.map { source -> source.copy(rows = source.rows.map { row ->
                var current = row
                if (source.id in setOf("addresses", "interfaces", "dns", "dns-suffix", "routes", "persistent-routes", "network-profiles", "neighbors")) {
                    val index = row.n("InterfaceIndex")
                    val adapterId = adapters.firstOrNull { it.index == index }?.id ?: "interface:$index"
                    val distinguishing = when (source.id) {
                        "routes", "persistent-routes" -> listOf("DestinationPrefix", "NextHop", "CompartmentId", "PolicyStore")
                        "addresses", "neighbors" -> listOf("IPAddress", "CompartmentId")
                        else -> listOf("AddressFamily", "CompartmentId")
                    }
                    current = current.copy(id = source.id + ":" + adapterId + "|" + distinguishing.joinToString("|") { row.f(it) })
                }
                if (source.id in setOf("connections", "listeners", "udp-endpoints")) {
                    val process = processById[row.f("OwningProcess")]
                    if (process != null) current = current.copy(fields = current.fields + mapOf(
                        "ProcessName" to process.f("Name"), "ExecutablePath" to process.f("ExecutablePath"), "ParentProcessId" to process.f("ParentProcessId")),
                        title = process.f("Name").ifBlank { "PID ${row.f("OwningProcess")}" } + " · " +
                            if (source.id == "connections") "${row.f("RemoteAddress")}:${row.f("RemotePort")}" else "${row.f("LocalAddress")}:${row.f("LocalPort")}")
                }
                current
            }) }
            return NetworkSnapshot(platform = "Windows ${System.getProperty("os.version")}", startedAt = started, finishedAt = System.currentTimeMillis(),
                elevated = elevated, userContext = rows("user-proxy").firstOrNull()?.f("UserSID").orEmpty(),
                adapters = adapters, routes = routes, listeners = listeners, sources = stableSources)
        }
    }
}
