package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Test

class LocalGeoIpTest {
    private val indexFile = File("src/main/assets/hop-geoip.idx")

    @Test
    fun bundledIndexMatchesPublicCountriesFromRoutingSets() {
        val lookup = LocalGeoIp { indexFile.inputStream() }
        assertThat(lookup.country("77.88.8.8")).isEqualTo("RU")
        assertThat(lookup.country("8.8.8.8")).isEqualTo("US")
        assertThat(lookup.country("1.1.1.1")).isEqualTo("AU")
        assertThat(lookup.country("2001:4860:4860::8888")).isEqualTo("US")
    }

    @Test
    fun privateAndNonLiteralAddressesStayUnknownWithoutOpeningIndex() {
        val lookup = LocalGeoIp { error("Index must not be opened") }
        assertThat(lookup.country("192.168.1.1")).isNull()
        assertThat(lookup.country("100.64.1.1")).isNull()
        assertThat(lookup.country("vpn.example")).isNull()
        assertThat(lookup.country("1.2.3.999")).isNull()
    }

    @Test
    fun invalidIndexIsRejected() {
        val lookup = LocalGeoIp { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val failure = runCatching { lookup.country("8.8.8.8") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
    }
}
