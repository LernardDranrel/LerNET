package app.lernet.desktop

import app.lernet.desktop.observation.ObservationCommandResult
import app.lernet.desktop.observation.ObservationCommandRunner
import app.lernet.desktop.observation.SystemObservationCommandRunner
import app.lernet.desktop.observation.powershellArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class ExpertPreflightResult(
    val checkedAtMs: Long,
    val summary: String,
    val facts: List<String>,
    val warnings: List<String>,
    val complete: Boolean,
)

/** A bounded local snapshot, not a connectivity probe or proof of another application's ownership. */
internal class WindowsExpertPreflight(
    private val runner: ObservationCommandRunner = SystemObservationCommandRunner,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    suspend fun read(): ExpertPreflightResult = runInterruptible(Dispatchers.IO) {
        if (!windows) return@runInterruptible unavailable("Чтение сетевых настроек доступно только в Windows.")
        try {
            val response = runner.run(powershellArguments(SCRIPT), TIMEOUT_MS)
            when {
                response.timedOut -> unavailable("Windows не завершила предварительный снимок за 8 секунд.")
                response.exitCode != 0 || response.outputTruncated || response.readError != null ->
                    unavailable("Windows не предоставила полный предварительный снимок.")
                else -> parse(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            unavailable("Не удалось прочитать предварительный снимок Windows.")
        }
    }

    companion object {
        internal const val TIMEOUT_MS = 8_000L
        private val DESTINATIONS = listOf("1.1.1.1", "129.1.1.1", "2606:4700:4700::1111")
        private const val LIMITATION =
            "Это локальный снимок: сторонний VPN или его фильтры могут отсутствовать в перечне Windows. " +
                "Перед включением можно открыть «Сеть устройства» для подробного анализа."

        internal fun unavailable(reason: String): ExpertPreflightResult = ExpertPreflightResult(
            System.currentTimeMillis(), "Предварительный анализ неполный", emptyList(), listOf(reason, LIMITATION), false,
        )

        internal fun parse(response: ObservationCommandResult): ExpertPreflightResult {
            require(response.output.length <= 131_072)
            val root = Json.parseToJsonElement(response.output.trim().removePrefix("\uFEFF")) as? JsonObject
                ?: error("Invalid preflight envelope")
            fun array(key: String, limit: Int): List<JsonObject> {
                val array = root[key] as? JsonArray ?: error("Missing preflight section")
                require(array.size <= limit)
                return array.map { it as? JsonObject ?: error("Invalid preflight entry") }
            }
            fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull.orEmpty().filterNot(Char::isISOControl).take(100)
            fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.booleanOrNull
            fun JsonObject.index(key: String): Int? = (this[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            val adapters = array("adapters", 256)
            val routes = array("routes", DESTINATIONS.size)
            val vpns = array("vpns", 128)
            val errors = root["errors"] as? JsonArray ?: error("Missing preflight errors")
            require(errors.size <= 8)
            val facts = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            var complete = errors.isEmpty()
            errors.forEach { error ->
                warnings += when ((error as? JsonPrimitive)?.contentOrNull) {
                    "adapters" -> "Не удалось прочитать свойства сетевых адаптеров."
                    "vpn-user", "vpn-global" -> "Не удалось прочитать один из перечней встроенного VPN Windows."
                    "routes" -> "Не удалось определить один из выбранных маршрутов Windows."
                    "limited" -> "Windows вернула слишком много записей; показана ограниченная часть снимка."
                    else -> "Один из источников Windows недоступен."
                }
            }
            require(routes.map { it.text("destination") }.distinct().size == routes.size)
            routes.forEach { route ->
                val destination = route.text("destination")
                require(destination in DESTINATIONS)
                val index = route.index("index")
                val adapter = adapters.firstOrNull { it.index("index") == index }
                val name = adapter?.text("name")?.ifBlank { null } ?: route.text("name").ifBlank { "неизвестный интерфейс" }
                if (index == null) {
                    facts += "$destination: Windows не нашла выбранный маршрут."
                } else {
                    facts += "$destination → «$name» (интерфейс $index)."
                    when {
                        adapter == null -> {
                            complete = false
                            warnings += "Для выбранного интерфейса $index нет сведений об адаптере; его тип неизвестен."
                        }
                        adapter.boolean("hardware") == false || adapter.boolean("virtual") == true ->
                            warnings += "Windows выбирает виртуальный интерфейс «$name». Это может быть текущий LerNET, " +
                                "другой VPN или виртуальная сеть; владелец по имени не определяется."
                        adapter.boolean("hardware") == null -> {
                            complete = false
                            warnings += "Windows не сообщила, является ли выбранный интерфейс «$name» физическим."
                        }
                    }
                }
            }
            if (routes.size != DESTINATIONS.size) {
                complete = false
                warnings += "Снимок не охватывает все контрольные IPv4 и IPv6 назначения."
            }
            val connected = vpns.filter { it.text("status").equals("Connected", true) }
            connected.take(4).forEach { vpn ->
                val name = vpn.text("name").ifBlank { "без названия" }
                val protocol = vpn.text("protocol").ifBlank { "протокол не сообщён" }
                val split = when (vpn.boolean("split")) {
                    true -> "разделение трафика включено"
                    false -> "разделение трафика выключено"
                    null -> "режим разделения не сообщён"
                }
                facts += "Встроенный VPN Windows «$name»: подключён, $protocol, $split."
            }
            if (connected.isNotEmpty()) {
                warnings +=
                    "Windows сообщает о подключённом встроенном VPN. Его маршруты или фильтры могут конкурировать с общим TUN; " +
                    "LerNET не отключает и не перенастраивает это подключение."
            }
            if (connected.size > 4) facts += "Других подключённых профилей встроенного VPN: ${connected.size - 4}."
            return ExpertPreflightResult(
                System.currentTimeMillis(),
                if (complete) "Текущие пути Windows прочитаны" else "Предварительный анализ неполный",
                facts.take(8), warnings.distinct().take(7) + LIMITATION, complete
            )
        }

        private val SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            ${'$'}errors = [System.Collections.Generic.List[string]]::new()
            ${'$'}adapters = @()
            try {
                ${'$'}all = @(Get-NetAdapter -IncludeHidden)
                if (${'$'}all.Count -gt 256) { ${'$'}errors.Add('limited') }
                ${'$'}adapters = @(${'$'}all | Select-Object -First 256 | ForEach-Object {
                    [pscustomobject]@{ index=[int]${'$'}_.ifIndex; name=[string]${'$'}_.Name
                        hardware=$(if (${'$'}null -eq ${'$'}_.HardwareInterface) { ${'$'}null } else { [bool]${'$'}_.HardwareInterface })
                        virtual=$(if (${'$'}null -eq ${'$'}_.Virtual) { ${'$'}null } else { [bool]${'$'}_.Virtual }) }
                })
            } catch { ${'$'}errors.Add('adapters') }
            ${'$'}routes = @('1.1.1.1', '129.1.1.1', '2606:4700:4700::1111' | ForEach-Object {
                ${'$'}destination = ${'$'}_
                try {
                    ${'$'}route = @(Find-NetRoute -RemoteIPAddress ${'$'}destination |
                        Where-Object { ${'$'}null -ne ${'$'}_.DestinationPrefix } | Select-Object -First 1)
                    if (${'$'}route.Count -eq 0) { throw 'No route' }
                    [pscustomobject]@{ destination=${'$'}destination; index=[int]${'$'}route[0].InterfaceIndex
                        name=[string]${'$'}route[0].InterfaceAlias }
                } catch {
                    ${'$'}errors.Add('routes')
                    [pscustomobject]@{ destination=${'$'}destination; index=${'$'}null; name='' }
                }
            })
            ${'$'}vpns = @()
            try { ${'$'}vpns += @(Get-VpnConnection) } catch { ${'$'}errors.Add('vpn-user') }
            try { ${'$'}vpns += @(Get-VpnConnection -AllUserConnection) } catch { ${'$'}errors.Add('vpn-global') }
            if (${'$'}vpns.Count -gt 128) { ${'$'}errors.Add('limited') }
            ${'$'}vpns = @(${'$'}vpns | Select-Object -First 128 | ForEach-Object {
                [pscustomobject]@{ name=[string]${'$'}_.Name; status=[string]${'$'}_.ConnectionStatus
                    protocol=[string]${'$'}_.TunnelType; split=[bool]${'$'}_.SplitTunneling }
            })
            ConvertTo-Json -InputObject ([pscustomobject]@{ adapters=${'$'}adapters; routes=${'$'}routes
                vpns=${'$'}vpns; errors=@(${'$'}errors | Select-Object -Unique) }) -Depth 5 -Compress
        """.trimIndent()
    }
}
