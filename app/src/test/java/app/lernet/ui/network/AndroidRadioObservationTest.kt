package app.lernet.ui.network

import app.lernet.engine.net.observation.SourceState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidRadioObservationTest {
    private val wifi = AndroidRadio(wifi = true, rssiDbm = -62, frequencyMhz = 5180, linkSpeedMbps = 433, metered = false)

    @Test fun withoutLocationTheNameIsHiddenHonestlyAndTheButtonIsOffered() {
        val source = AndroidRadioText.source(wifi, 7)
        val row = source.rows.single()
        assertThat(row.fields["Имя сети"]).isEqualTo(AndroidRadioText.NAME_NEEDS_PERMISSION)
        assertThat(row.fields["Сигнал"]).isEqualTo("-62 дБм · хороший")
        assertThat(row.fields["Частота"]).isEqualTo("5180 МГц · 5 ГГц")
        assertThat(source.complete).isFalse()
        assertThat(AndroidRadioText.needsPermission(source)).isTrue()
    }

    @Test fun grantedLocationShowsTheNameAndNoButton() {
        val source = AndroidRadioText.source(wifi.copy(ssid = "Home", locationGranted = true), 7)
        assertThat(source.rows.single().fields["Имя сети"]).isEqualTo("Home")
        assertThat(source.complete).isTrue()
        assertThat(AndroidRadioText.needsPermission(source)).isFalse()
    }

    @Test fun locationSwitchedOffIsNotAPermissionProblem() {
        val source = AndroidRadioText.source(wifi.copy(locationGranted = true, locationOn = false), 7)
        assertThat(source.rows.single().fields["Имя сети"]).isEqualTo(AndroidRadioText.NAME_LOCATION_OFF)
        assertThat(AndroidRadioText.needsPermission(source)).isFalse()
    }

    @Test fun cellularNeverClaimsAGenerationItDidNotAskFor() {
        val source = AndroidRadioText.source(AndroidRadio(wifi = false, cellular = true, operator = "Оператор", metered = true, roaming = false), 7)
        val row = source.rows.single()
        assertThat(row.title).isEqualTo("Мобильная сеть")
        assertThat(row.fields["Роуминг"]).isEqualTo("Нет")
        assertThat(row.fields["Поколение сети (4G, 5G)"]).contains("не запрашивает")
        assertThat(source.complete).isTrue()
    }

    @Test fun noNetworkIsEmptyNotHealthy() {
        val source = AndroidRadioText.source(AndroidRadio(wifi = false), 7)
        assertThat(source.state).isEqualTo(SourceState.EMPTY)
        assertThat(source.rows).isEmpty()
    }

    @Test fun bandsAndSignalWords() {
        assertThat(AndroidRadioText.band(2437)).isEqualTo("2,4 ГГц")
        assertThat(AndroidRadioText.band(6115)).isEqualTo("6 ГГц")
        assertThat(AndroidRadioText.signalWord(-50)).isEqualTo("отличный")
        assertThat(AndroidRadioText.signalWord(-80)).isEqualTo("слабый")
    }

    @Test fun collectorPutsTheRadioSourceBeforeVpnAndSurvivesItsFailure() {
        val ok = AndroidNetworkCollector(radioReader = { wifi }) { emptyList() }.collect { 5 }
        val ids = ok.sources.map { it.id }
        assertThat(ids.indexOf("wlan")).isLessThan(ids.indexOf("vpn"))
        val denied = AndroidNetworkCollector(radioReader = { throw SecurityException("secret-detail") }) { emptyList() }.collect { 5 }
        val wlan = denied.sources.single { it.id == "wlan" }
        assertThat(wlan.state).isEqualTo(SourceState.ACCESS_DENIED)
        assertThat(wlan.detail).doesNotContain("secret-detail")
        assertThat(AndroidNetworkCollector { emptyList() }.collect { 5 }.sources.none { it.id == "wlan" }).isTrue()
    }
}
