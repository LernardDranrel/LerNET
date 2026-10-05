package app.lernet.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class PhysicalNetwork(val name: String, val index: Int, val metric: Long)

/** Selects a real connected adapter without consulting a competing VPN's default route. */
internal object WindowsPhysicalNetwork {
    fun select(): PhysicalNetwork = choose(readWindowsAdapterJson(SCRIPT))

    internal fun choose(raw: String): PhysicalNetwork {
        val array = Json.parseToJsonElement(raw.trim().removePrefix("\uFEFF")) as? JsonArray
            ?: error("Windows вернула неверный список физических адаптеров")
        return array.mapNotNull { entry ->
            val value = entry as? JsonObject ?: return@mapNotNull null
            val name = (value["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            val index = (value["index"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val metric = (value["metric"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (name.isBlank() || name.length > 256 || name.any { it.isISOControl() } || index <= 0 || metric < 0) return@mapNotNull null
            PhysicalNetwork(name, index, metric)
        }.minWithOrNull(compareBy<PhysicalNetwork> { it.metric }.thenBy { it.index })
            ?: error("Нет подключённого физического адаптера. Подключите Wi‑Fi или Ethernet; параметры другого VPN не изменяются.")
    }

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${ '$' }false)
        ${'$'}physical = @(Get-NetAdapter -Physical | Where-Object Status -eq 'Up')
        ${'$'}routes = @(Get-NetRoute -PolicyStore ActiveStore | Where-Object { ${'$'}_.DestinationPrefix -in @('0.0.0.0/0', '::/0') })
        ${'$'}rows = @(${ '$' }physical | ForEach-Object {
            ${'$'}adapter = ${'$'}_
            ${'$'}metrics = @(${ '$' }routes | Where-Object InterfaceIndex -eq ${'$'}adapter.ifIndex | ForEach-Object {
                [long]${'$'}_.RouteMetric + [long]${'$'}_.InterfaceMetric
            } | Sort-Object)
            [pscustomobject]@{ name = ${'$'}adapter.Name; index = [int]${'$'}adapter.ifIndex; metric = $(if (${ '$' }metrics.Count) { ${'$'}metrics[0] } else { [long]2147483647 }) }
        })
        ConvertTo-Json -InputObject ${'$'}rows -Compress
    """.trimIndent()
}
