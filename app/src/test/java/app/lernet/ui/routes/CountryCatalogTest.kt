package app.lernet.ui.routes

import app.lernet.routing.GeoRuleSets
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CountryCatalogTest {
    @Test
    fun fullCatalogIsTheBundledRuleSet() {
        assertThat(CountryNames.all.map { it.code }).containsExactlyElementsIn(GeoRuleSets.bundled).inOrder()
        assertThat(CountryNames.all.size).isGreaterThan(200)
        val russia = CountryNames.all.first { it.code == "ru" }
        assertThat(russia.nameRu).contains("Росс")
        CountryCatalog.frequent.forEach { code ->
            assertThat(GeoRuleSets.isBundled(code)).isTrue()
        }
    }

    @Test
    fun shortCodeDoesNotSwallowNeighbourNames() {
        val labels = CountryNames.all
        assertThat(CountryCatalog.groups("us", emptyList(), labels).rest).containsExactly("us")
        assertThat(CountryCatalog.groups("гер", emptyList(), labels).rest).contains("de")
        assertThat(CountryCatalog.groups("сша", emptyList(), labels).rest).contains("us")
        assertThat(CountryCatalog.groups("неттакой", emptyList(), labels).rest).isEmpty()
    }

    @Test
    fun blankQueryPinsFrequentAndKeepsRecentThatAreNotFrequent() {
        val labels = listOf(
            CountryLabel("ru", "Россия", "Russia"),
            CountryLabel("de", "Германия", "Germany"),
            CountryLabel("fr", "Франция", "France"),
            CountryLabel("zz", "Зет", "Zed"),
        )
        val groups = CountryCatalog.groups("", listOf("de", "zz", "ru"), labels)
        assertThat(groups.frequent).containsExactly("ru", "de", "fr").inOrder()
        assertThat(groups.recent).containsExactly("zz")
        assertThat(groups.rest).containsExactly("de", "zz", "ru", "fr").inOrder()
    }

    @Test
    fun pinMovesTheCodeFirstAndCapsTheList() {
        assertThat(CountryCatalog.pin(listOf("de", "fr"), "RU")).isEqualTo("ru,de,fr")
        assertThat(CountryCatalog.pin(listOf("ru", "de"), "ru")).isEqualTo("ru,de")
        val capped = CountryCatalog.pin(listOf("a", "b", "c", "d", "e", "f"), "ru")
        assertThat(capped.split(",")).containsExactly("ru", "a", "b", "c", "d", "e").inOrder()
    }
}
