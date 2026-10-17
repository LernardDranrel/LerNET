package app.lernet.config.parse

import app.lernet.config.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUnusedPartsTest {
    private val full = """
        {
          "log": {"level": "info"},
          "inbounds": [{"type":"tun","tag":"tun-in"},{"type":"mixed","tag":"mixed-in"}],
          "outbounds": [
            {"type":"selector","tag":"auto-pick","outbounds":["edge"]},
            {"type":"urltest","tag":"fastest","outbounds":["edge"]},
            {"type":"vless","tag":"edge","server":"h","server_port":443,"uuid":"u"},
            {"type":"direct","tag":"direct"},
            {"type":"block","tag":"block"}
          ],
          "route": {"rules":[{"domain":["a"]},{"ip_cidr":["1.1.1.1/32"]}], "rule_set":[{"tag":"x"}], "final":"auto-pick"},
          "dns": {"servers":[{"type":"local","tag":"local"}]},
          "experimental": {}
        }
    """.trimIndent()

    @Test
    fun fullConfigListsEverythingThatIsDropped() {
        val result = JsonConfigParser.parse(full, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        val parts = result.drafts.single().unusedParts
        assertEquals(
            listOf(
                "маршрутизация (route, правил: 2, наборов правил: 1) — работают правила LerNET",
                "входы (inbounds: 2) — LerNET создаёт свои",
                "группы selector/urltest: auto-pick, fastest — выбор сервера делает LerNET",
                "служебные outbound (direct/block/dns): direct, block — у LerNET свои",
                "раздел «experimental»",
                "раздел «log»",
            ),
            parts,
        )
        assertEquals(parts, result.unusedParts)
        assertEquals(1, result.drafts.single().outbounds.size)
    }

    @Test
    fun outboundsAndDnsOnlyHaveNothingUnused() {
        val raw = """{"outbounds":[{"type":"vless","tag":"edge","server":"h","server_port":443,"uuid":"u"}],"dns":{"servers":[]}}"""
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        assertTrue(result.unusedParts.isEmpty())
    }

    @Test
    fun bareOutboundHasNothingUnused() {
        val raw = """{"type":"vless","tag":"edge","server":"h","server_port":443,"uuid":"u"}"""
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        assertTrue(result.unusedParts.isEmpty())
    }

    @Test
    fun arrayOfOutboundsReportsServiceEntries() {
        val raw = """[{"type":"vless","tag":"edge","server":"h","server_port":443,"uuid":"u"},{"type":"dns","tag":"dns-out"}]"""
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        assertEquals(listOf("служебные outbound (direct/block/dns): dns-out — у LerNET свои"), result.unusedParts)
    }
}
