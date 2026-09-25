package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AsnCacheTest {
    @Test
    fun originKeepsAsnAndIsoCountryOnly() {
        val parsed = CymruAsn.parseOrigin("15169 | 8.8.8.0/24 | US | arin | 2011-01-01")
        assertThat(parsed).isEqualTo(CymruAsn.Origin(15169, "US"))
        assertThat(CymruAsn.parseOrigin("15169 | 8.8.8.0/24 | МГУ | arin | 2011-01-01")?.country).isNull()
        assertThat(CymruAsn.parseOrigin("nope | 1.0.0.0/8 | US | arin | 2011-01-01")).isNull()
    }

    @Test
    fun orgIsTheFifthFieldOnly() {
        val raw = "15169 | US | arin | 2000-03-30 | GOOGLE - Google LLC, US"
        assertThat(CymruAsn.parseOrg(raw)).isEqualTo("GOOGLE - Google LLC, US")
        assertThat(CymruAsn.parseOrg("15169 | US | arin | 2000-03-30")).isNull()
        assertThat(CymruAsn.parseOrg(null)).isNull()
        assertThat(CymruAsn.parseOrg("")).isNull()
    }

    @Test
    fun lookupAsksOriginThenNameAndOmitsAMissingOrg() {
        val seen = mutableListOf<String>()
        val fact = CymruAsn.lookup("1.2.3.4") { name ->
            seen += name
            if (name == "4.3.2.1.origin.asn.cymru.com") {
                "15169 | 1.2.3.0/24 | US | arin | 2011-01-01"
            } else {
                null
            }
        }
        assertThat(seen).containsExactly(
            "4.3.2.1.origin.asn.cymru.com",
            "AS15169.asn.cymru.com",
        ).inOrder()
        assertThat(fact).isEqualTo(AsnFact(country = "US", asn = 15169, org = null))
    }

    @Test
    fun lookupKeepsTheOrgWhenTheNameQueryAnswers() {
        val fact = CymruAsn.lookup("8.8.8.8") { name ->
            if (name.endsWith(".origin.asn.cymru.com")) {
                "15169 | 8.8.8.0/24 | US | arin | 2011-01-01"
            } else {
                "15169 | US | arin | 2000-03-30 | GOOGLE - Google LLC, US"
            }
        }
        assertThat(fact).isEqualTo(AsnFact("US", 15169, "GOOGLE - Google LLC, US"))
    }

    @Test
    fun aMissedOriginDoesNotAskForAName() {
        var calls = 0
        val fact = CymruAsn.lookup("8.8.8.8") {
            calls += 1
            null
        }
        assertThat(fact).isNull()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun privateAndReservedAddressesAreNotQueried() {
        listOf(
            "10.0.0.1",
            "127.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "224.0.0.1",
            "not-an-ip",
        ).forEach { ip ->
            var calls = 0
            assertThat(
                CymruAsn.lookup(ip) {
                    calls += 1
                    "x"
                }
            ).isNull()
            assertThat(calls).isEqualTo(0)
        }
    }

    @Test
    fun aHitIsReusedForADayAndAMissIsNotStored() {
        var now = 0L
        var fetches = 0
        val cache = DailyAsnCache(nowMs = { now }, fetch = { _ ->
            fetches += 1
            AsnFact("US", 15169, "GOOGLE")
        })
        assertThat(cache.lookup("8.8.8.8")?.asn).isEqualTo(15169)
        assertThat(cache.lookup("8.8.8.8")?.org).isEqualTo("GOOGLE")
        assertThat(fetches).isEqualTo(1)
        now += 86_400_000L
        assertThat(cache.lookup("8.8.8.8")?.country).isEqualTo("US")
        assertThat(fetches).isEqualTo(2)

        var misses = 0
        val cold = DailyAsnCache(nowMs = { 0L }, fetch = { _ ->
            misses += 1
            null
        })
        assertThat(cold.lookup("1.1.1.1")).isNull()
        assertThat(cold.lookup("1.1.1.1")).isNull()
        assertThat(misses).isEqualTo(2)
    }

    @Test
    fun theCacheSkipsPrivateAddressesWithoutAFetch() {
        var fetches = 0
        val cache = DailyAsnCache(nowMs = { 0L }, fetch = { _ ->
            fetches += 1
            AsnFact("US", 1, "X")
        })
        assertThat(cache.lookup("10.0.0.1")).isNull()
        assertThat(fetches).isEqualTo(0)
    }

    @Test
    fun txtAnswerDecodesTheCharacterString() {
        val text = "15169 | 8.8.8.0/24 | US | arin | 2011-01-01"
        val packet = txtResponse("example.com", text)
        assertThat(DnsTxt.decode(packet, packet.size)).isEqualTo(text)
    }

    @Test
    fun aShortPacketIsNotATxtAnswer() {
        val packet = txtResponse("example.com", "hello")
        assertThat(DnsTxt.decode(packet, packet.size - 1)).isNull()
        assertThat(DnsTxt.decode(ByteArray(12), 12)).isNull()
    }

    private fun txtResponse(name: String, text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val rdata = byteArrayOf(body.size.toByte()) + body
        val header = ByteArray(12)
        header[5] = 1
        header[7] = 1
        val question = dnsName(name) + byteArrayOf(0, 16, 0, 1)
        val answer = byteArrayOf(0xC0.toByte(), 0x0C) +
            byteArrayOf(0, 16, 0, 1, 0, 0, 0, 0) +
            byteArrayOf((rdata.size shr 8).toByte(), (rdata.size and 0xff).toByte()) +
            rdata
        return header + question + answer
    }

    private fun dnsName(name: String): ByteArray {
        val out = ArrayList<Byte>()
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        out += 0
        return out.toByteArray()
    }
}
