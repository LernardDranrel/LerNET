package app.lernet.ui.routes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainFieldTest {
    @Test
    fun exactTokenStaysDomainAndStarBecomesSuffix() {
        val (domains, suffixes) = DomainField.split("a.com, *.b.com *c.com")
        assertThat(domains).containsExactly("a.com")
        assertThat(suffixes).containsExactly("b.com", "c.com").inOrder()
    }

    @Test
    fun joinRoundTripsExactAndGlob() {
        val joined = DomainField.join(listOf("a.com"), listOf("b.com"))
        assertThat(joined).isEqualTo("a.com, *.b.com")
        val (domains, suffixes) = DomainField.split(joined)
        assertThat(domains).containsExactly("a.com")
        assertThat(suffixes).containsExactly("b.com")
    }

    @Test
    fun countrySelectionCyclesIncludedExcludedAndEmpty() {
        val added = GeoIpCodes.select(emptyList(), "ru")
        val excluded = GeoIpCodes.select(added, "ru")
        val removed = GeoIpCodes.select(excluded, "ru")
        val marked = GeoIpCodes.negate(emptyList(), "ru")
        val flipped = GeoIpCodes.negate(added, "ru")
        val restored = GeoIpCodes.negate(marked, "ru")
        assertThat(added).containsExactly("ru")
        assertThat(excluded).containsExactly("!ru")
        assertThat(removed).isEmpty()
        assertThat(marked).containsExactly("!ru")
        assertThat(flipped).containsExactly("!ru")
        assertThat(restored).containsExactly("ru")
    }
}
