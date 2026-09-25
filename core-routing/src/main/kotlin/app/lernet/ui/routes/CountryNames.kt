package app.lernet.ui.routes

import app.lernet.routing.GeoRuleSets
import java.util.Locale

/** Russian and English names from the platform locale data, not a hand-written table. */
object CountryNames {
    private val russian = Locale.forLanguageTag("ru")
    private val english = Locale.ENGLISH

    val all: List<CountryLabel> = GeoRuleSets.bundled.map(::label)

    fun label(code: String): CountryLabel {
        val region = Locale.Builder().setRegion(code.uppercase()).build()
        return CountryLabel(
            code = code.lowercase(),
            nameRu = display(region, russian, code),
            nameEn = display(region, english, code),
        )
    }

    private fun display(region: Locale, language: Locale, code: String): String {
        val name = region.getDisplayCountry(language).trim()
        if (name.isEmpty() || name.equals(code, ignoreCase = true)) return code.uppercase()
        return name
    }
}
