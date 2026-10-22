package app.lernet.desktop.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowsWlanTextTest {
    private val english = """
        |
        |There is 1 interface on the system:
        |
        |    Name                   : Wi-Fi
        |    Description            : Intel(R) Wi-Fi 6 AX201 160MHz
        |    GUID                   : 00000000-0000-0000-0000-000000000000
        |    Physical address       : aa:bb:cc:dd:ee:ff
        |    State                  : connected
        |    SSID                   : HomeNet
        |    AP BSSID               : 11:22:33:44:55:66
        |    Band                   : 5 GHz
        |    Channel                : 44
        |    Radio type             : 802.11ax
        |    Authentication         : WPA2-Personal
        |    Receive rate (Mbps)    : 1201
        |    Transmit rate (Mbps)   : 960
        |    Signal                 : 86%
        |    Profile                : HomeNet
        |
        |    Hosted network status  : Not available
    """.trimMargin()

    private val russian = """
        |В системе есть 1 интерфейс:
        |
        |    Имя                    : Беспроводная сеть
        |    Состояние              : подключено
        |    SSID                   : Дача
        |    BSSID                  : 11:22:33:44:55:66
        |    Тип радио              : 802.11n
        |    Диапазон               : 2,4 ГГц
        |    Канал                  : 6
        |    Проверка подлинности   : WPA2-Personal
        |    Скорость приема (Мбит/с)   : 144
        |    Скорость передачи (Мбит/с) : 72
        |    Сигнал                 : 54%
    """.trimMargin()

    @Test
    fun `english netsh becomes the same wifi row as on android`() {
        val row = WindowsWlanText.parse(english).single()
        assertThat(row.id).isEqualTo("wifi")
        assertThat(row.title).isEqualTo("Wi-Fi")
        assertThat(row.fields["Имя сети"]).isEqualTo("HomeNet")
        assertThat(row.fields["Сигнал"]).isEqualTo("86% · отличный")
        assertThat(row.fields["Частота"]).isEqualTo("5 ГГц")
        assertThat(row.fields["Канал"]).isEqualTo("44")
        assertThat(row.fields["Стандарт"]).isEqualTo("802.11ax · Wi-Fi 6")
        assertThat(row.fields["Скорость связи с роутером"]).isEqualTo("1201 Мбит/с (приём)")
        assertThat(row.fields["Защита сети"]).isEqualTo("WPA2-Personal")
    }

    @Test
    fun `russian netsh is read too`() {
        val row = WindowsWlanText.parse(russian).single()
        assertThat(row.title).isEqualTo("Беспроводная сеть")
        assertThat(row.fields["Имя сети"]).isEqualTo("Дача")
        assertThat(row.fields["Сигнал"]).isEqualTo("54% · средний")
        assertThat(row.fields["Частота"]).isEqualTo("2,4 ГГц")
        assertThat(row.fields["Канал"]).isEqualTo("6")
        assertThat(row.fields["Скорость передачи"]).isEqualTo("72 Мбит/с")
    }

    @Test
    fun `other languages keep the language-free values and drop the rest`() {
        val german = """
            |    Name                   : WLAN
            |    Status                 : Verbunden
            |    SSID                   : Buero
            |    Funktyp                : 802.11ac
            |    Kanal                  : 36
            |    Signal                 : 70%
        """.trimMargin()
        val row = WindowsWlanText.parse(german).single()
        assertThat(row.fields["Имя сети"]).isEqualTo("Buero")
        assertThat(row.fields["Стандарт"]).isEqualTo("802.11ac · Wi-Fi 5")
        assertThat(row.fields).doesNotContainKey("Канал")
    }

    @Test
    fun `disconnected adapter gives no row and hidden name says why`() {
        assertThat(WindowsWlanText.parse("    Name : Wi-Fi\n    State : disconnected\n")).isEmpty()
        val hidden = "    Name : Wi-Fi\n    SSID :\n    Signal : 60%\n\nNetwork shell commands need location permission to access WLAN information."
        assertThat(WindowsWlanText.parse(hidden).single().fields["Имя сети"]).isEqualTo(WindowsWlanText.NAME_HIDDEN)
    }
}
