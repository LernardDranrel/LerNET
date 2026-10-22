package app.lernet.ui.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import app.lernet.engine.net.observation.EvidenceRow
import app.lernet.engine.net.observation.ObservationSource
import app.lernet.engine.net.observation.SourceState

/** What Android tells about the current Wi-Fi and mobile network. Read once per snapshot, never stored. */
internal data class AndroidRadio(
    val wifi: Boolean,
    /** Null while Android hides the name (no location permission or location switched off). */
    val ssid: String? = null,
    val locationGranted: Boolean = false,
    val locationOn: Boolean = true,
    val rssiDbm: Int? = null,
    val frequencyMhz: Int? = null,
    val linkSpeedMbps: Int? = null,
    val cellular: Boolean = false,
    val operator: String? = null,
    val metered: Boolean? = null,
    val roaming: Boolean? = null,
)

internal fun interface AndroidRadioReader { fun read(): AndroidRadio }

internal class SystemRadioReader(context: Context) : AndroidRadioReader {
    private val app = context.applicationContext

    @Suppress("DEPRECATION")
    override fun read(): AndroidRadio {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        // The phone's own network, not LerNET's VPN: the first non-VPN network that carries internet.
        val caps = connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
            .firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val granted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationOn = runCatching {
            val location = app.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else true
        }.getOrDefault(true)
        val info = if (wifi) runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull() else null
        val ssid = info?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        val telephony = runCatching { app.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return AndroidRadio(
            wifi = wifi,
            ssid = ssid,
            locationGranted = granted,
            locationOn = locationOn,
            rssiDbm = info?.rssi?.takeIf { it in -127..-1 },
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            cellular = cellular,
            operator = if (cellular) telephony?.networkOperatorName?.takeIf { it.isNotBlank() } else null,
            metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            roaming = if (Build.VERSION.SDK_INT >= 28) caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) } else null,
        )
    }
}

internal object AndroidRadioText {
    const val NAME_NEEDS_PERMISSION =
        "Скрыто. Android выдаёт имя сети Wi-Fi только приложениям с доступом к геолокации; LerNET не определяет и не хранит местоположение"
    const val NAME_LOCATION_OFF = "Скрыто: на телефоне выключена геолокация, без неё Android не сообщает имя сети"
    const val GENERATION_NOT_ASKED = "Скрыто: Android сообщает только с разрешением на звонки и состояние телефона; LerNET его не запрашивает"

    fun needsPermission(source: ObservationSource): Boolean =
        source.id == "wlan" && source.rows.any { it.fields["Имя сети"] == NAME_NEEDS_PERMISSION }

    fun source(radio: AndroidRadio, capturedAt: Long): ObservationSource {
        val rows = buildList {
            if (radio.wifi) add(EvidenceRow("wifi", "Wi-Fi", linkedMapOf(
                "Имя сети" to when {
                    radio.ssid != null -> radio.ssid
                    !radio.locationGranted -> NAME_NEEDS_PERMISSION
                    !radio.locationOn -> NAME_LOCATION_OFF
                    else -> "Android не сообщил имя сети"
                },
                "Сигнал" to (radio.rssiDbm?.let { "$it дБм · ${signalWord(it)}" } ?: "Не передан"),
                "Частота" to (radio.frequencyMhz?.let { "$it МГц · ${band(it)}" } ?: "Не передана"),
                "Скорость связи с роутером" to (radio.linkSpeedMbps?.let { "$it Мбит/с" } ?: "Не передана"),
                "Лимитная сеть" to yesNo(radio.metered),
            )))
            if (radio.cellular) add(EvidenceRow("cellular", "Мобильная сеть", linkedMapOf(
                "Оператор" to (radio.operator ?: "Не передан"),
                "Лимитная сеть" to yesNo(radio.metered),
                "Роуминг" to yesNo(radio.roaming),
                "Поколение сети (4G, 5G)" to GENERATION_NOT_ASKED,
            )))
        }
        return ObservationSource(
            "wlan", "Wi-Fi и мобильная сеть",
            "Сеть, через которую телефон выходит в интернет: сигнал, частота, оператор. Данные не сохраняются и никуда не отправляются.",
            if (rows.isEmpty()) SourceState.EMPTY else SourceState.AVAILABLE,
            rows,
            detail = if (rows.isEmpty()) "Сейчас нет подключения по Wi-Fi или мобильной сети" else "",
            capturedAt = capturedAt,
            complete = rows.isNotEmpty() && (!radio.wifi || radio.ssid != null),
        )
    }

    private fun yesNo(value: Boolean?) = when (value) { true -> "Да"; false -> "Нет"; null -> "Не передано" }

    fun band(mhz: Int) = when (mhz) {
        in 2400..2500 -> "2,4 ГГц"
        in 4900..5900 -> "5 ГГц"
        in 5925..7125 -> "6 ГГц"
        else -> "другой диапазон"
    }

    fun signalWord(dbm: Int) = when {
        dbm >= -55 -> "отличный"
        dbm >= -67 -> "хороший"
        dbm >= -75 -> "средний"
        else -> "слабый"
    }
}
