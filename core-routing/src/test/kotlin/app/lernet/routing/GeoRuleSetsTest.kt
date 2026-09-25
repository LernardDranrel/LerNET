package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoRuleSetsTest {
    @Test
    fun bundledListIsTheOfficialRuleSetNotAStub() {
        assertThat(GeoRuleSets.bundled.size).isGreaterThan(200)
        assertThat(GeoRuleSets.bundled).containsAtLeast("ru", "us", "de", "cn", "ir", "br", "jp")
        assertThat(GeoRuleSets.bundled).doesNotContain("private")
        GeoRuleSets.bundled.forEach { code ->
            assertThat(code).matches("[a-z]{2}")
        }
    }

    @Test
    fun unknownCodeIsMissingAndPrivateIsNotAFile() {
        assertThat(GeoRuleSets.isBundled("RU")).isTrue()
        assertThat(GeoRuleSets.isBundled("zz")).isFalse()
        assertThat(GeoRuleSets.isBundled("private")).isFalse()
        assertThat(GeoRuleSets.missing(listOf("ru", "!zz", "private"))).containsExactly("zz")
        assertThat(GeoRuleSets.countryCode("!private")).isNull()
        assertThat(GeoRuleSets.tags(listOf("DE"))).containsExactly("geoip-de")
    }
}
