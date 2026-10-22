package app.lernet.ui.network

import app.lernet.config.net.AppTraffic
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.engine.net.observation.*
import app.lernet.engine.net.LocalGeoIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream

data class NetworkObservationUiState(
    val snapshot: NetworkSnapshot? = null,
    val previous: NetworkSnapshot? = null,
    val collecting: Boolean = false,
    val checkingIp: Boolean = false,
    val externalIp: String? = null,
    val externalIpAt: Long? = null,
    val countryCode: String? = null,
    val ipError: String? = null,
    val notice: String? = null,
)

class NetworkObservationViewModel @JvmOverloads constructor(
    application: Application,
    private val collectSnapshot: () -> NetworkSnapshot = { AndroidNetworkCollector(SystemRadioReader(application), ConnectivityNetworkReader(application)).collect() },
    private val openIpConnection: () -> HttpURLConnection = { URL(IP_URL).openConnection() as HttpURLConnection },
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(NetworkObservationUiState())
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private val ipLock = Any()
    /** Cancels the running IP request: disconnect or OkHttp cancel. */
    private var ipCancel: (() -> Unit)? = null
    private var exportJob: Job? = null
    private var ipJob: Job? = null
    @Volatile private var ipGeneration = 0L
    private val reportJson = Json { prettyPrint = true }
    private val geoIp = LocalGeoIp { application.assets.open("hop-geoip.idx") }

    init { refresh() }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        invalidateIpCheck()
        mutable.value = mutable.value.copy(collecting = true, notice = null)
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { collectSnapshot() }
                mutable.value = mutable.value.copy(snapshot = snapshot, previous = mutable.value.snapshot,
                    externalIp = null, externalIpAt = null, countryCode = null, ipError = null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(notice = app.lernet.engine.text.UserText.withReason("Снимок не обновлён", error).trimEnd('.') + ". Показаны ранее полученные данные; попробуйте обновить ещё раз.")
            } finally { mutable.value = mutable.value.copy(collecting = false) }
        }
    }

    /** Only a user action can reach the external observer, over Android's current default path. */
    fun checkExternalIp() {
        if (mutable.value.checkingIp || mutable.value.collecting) return
        val generation = ipGeneration
        mutable.value = mutable.value.copy(checkingIp = true, ipError = null, externalIp = null, externalIpAt = null, countryCode = null)
        ipJob = viewModelScope.launch {
            try {
                val ip = withContext(Dispatchers.IO) {
                    // VPN on: only through LerNET's internal listener to the VPN server, never directly.
                    if (AppTraffic.routed) return@withContext readRoutedIp(generation)
                    val connection = openIpConnection()
                    val cancel = { connection.disconnect() }
                    try {
                        currentCoroutineContext().ensureActive()
                        synchronized(ipLock) {
                            if (generation != ipGeneration) throw kotlinx.coroutines.CancellationException("Снимок обновлён")
                            ipCancel = cancel
                        }
                        connection.connectTimeout = 30_000
                        connection.readTimeout = 30_000
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.setRequestProperty("Cache-Control", "no-cache")
                        require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                        connection.inputStream.use(::readExternalIp)
                    } finally {
                        connection.disconnect()
                        synchronized(ipLock) { if (ipCancel === cancel) ipCancel = null }
                    }
                }
                val country = withContext(Dispatchers.IO) { runCatching { geoIp.country(ip) }.getOrNull() }
                if (generation == ipGeneration) mutable.value = mutable.value.copy(externalIp = ip, externalIpAt = System.currentTimeMillis(), countryCode = country)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (generation == ipGeneration) mutable.value = mutable.value.copy(ipError = app.lernet.engine.text.UserText.withReason("Адрес не получен", error))
            } finally { if (generation == ipGeneration) mutable.value = mutable.value.copy(checkingIp = false) }
        }
    }

    fun export(uri: Uri) {
        val snapshot = mutable.value.snapshot ?: return
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val json = reportJson.encodeToString(snapshot)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                    ?: error("Файл не удалось открыть")
            } }
            mutable.value = mutable.value.copy(notice = if (result.isSuccess) "Снимок сохранён в выбранный файл"
                else app.lernet.engine.text.UserText.withReason("Не удалось сохранить", result.exceptionOrNull()))
        }
    }

    private fun invalidateIpCheck() {
        val connection = synchronized(ipLock) {
            ipGeneration++
            ipCancel.also { ipCancel = null }
        }
        ipJob?.cancel()
        connection?.invoke()
        mutable.value = mutable.value.copy(checkingIp = false, externalIp = null, externalIpAt = null, countryCode = null, ipError = null)
    }

    override fun onCleared() { invalidateIpCheck(); super.onCleared() }

    private suspend fun readRoutedIp(generation: Long): String {
        val request = Request.Builder().url(IP_URL).header("Cache-Control", "no-cache").build()
        val call = AppTraffic.client(ipClient).newCall(request)
        val cancel = { call.cancel() }
        try {
            currentCoroutineContext().ensureActive()
            synchronized(ipLock) {
                if (generation != ipGeneration) throw kotlinx.coroutines.CancellationException("Снимок обновлён")
                ipCancel = cancel
            }
            return call.execute().use { response ->
                require(response.code == 200) { "HTTP ${response.code}" }
                val body = response.body ?: error("Пустой ответ")
                body.byteStream().use(::readExternalIp)
            }
        } finally {
            call.cancel()
            synchronized(ipLock) { if (ipCancel === cancel) ipCancel = null }
        }
    }

    private companion object {
        const val IP_URL = "https://api.ipify.org"
        val ipClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
        }
    }
}

/** Bounded response parsing; never resolves a host name or uses Android APIs. */
internal fun readExternalIp(stream: InputStream): String {
    val bytes = ByteArray(48)
    var size = 0
    while (true) {
        val next = stream.read()
        if (next < 0) break
        require(size < bytes.size) { "Слишком длинный ответ сервиса" }
        bytes[size++] = next.toByte()
    }
    val text = String(bytes, 0, size, Charsets.UTF_8).trim()
    val valid = NetworkRouteSelection.isNumericAddress(text)
    require(valid) { "Сервис не вернул IP-адрес" }
    return text
}
