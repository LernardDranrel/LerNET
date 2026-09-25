package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HopPathTest {
    @Test
    fun lineShowsCountryAndIpAndAVisibleTimeout() {
        val line = HopPath.line(
            phone = "телефон",
            stops = listOf(
                HopStop("10.0.0.1", country = "de", timedOut = false),
                HopStop("203.0.113.5", country = null, timedOut = true),
            ),
            unknown = "страна неизвестна",
            timeout = "таймаут",
        )
        assertThat(line).isEqualTo(
            """
            телефон
            1  10.0.0.1  DE
            2  203.0.113.5  страна неизвестна → таймаут
            """.trimIndent(),
        )
        assertThat(line).doesNotContain("МГУ")
    }

    @Test
    fun cachedAsnAndOrgAreAppendedAndAMissStaysBlank() {
        val known = HopPath.line(
            "телефон",
            listOf(HopStop("203.0.113.9", country = "DE", timedOut = false, asn = 3320, org = "DTAG")),
            "страна неизвестна",
            "таймаут",
        )
        assertThat(known).isEqualTo(
            """
            телефон
            1  203.0.113.9  DE · AS3320 DTAG
            """.trimIndent(),
        )
        val miss = HopPath.line(
            "телефон",
            listOf(HopStop("203.0.113.9", country = "DE", timedOut = false, asn = null, org = null)),
            "страна неизвестна",
            "таймаут",
        )
        assertThat(miss).isEqualTo(
            """
            телефон
            1  203.0.113.9  DE
            """.trimIndent(),
        )
        assertThat(miss).doesNotContain("AS")
        assertThat(miss).doesNotContain("МГУ")
    }

    @Test
    fun aHopWithNoReplyIsOnlyTheTimeoutWord() {
        val line = HopPath.line(
            "телефон",
            listOf(HopStop(address = null, country = null, timedOut = true)),
            "страна неизвестна",
            "таймаут",
        )
        assertThat(line).isEqualTo(
            """
            телефон
            1  *  таймаут
            """.trimIndent(),
        )
    }

    @Test
    fun anOrgShapedCountryIsDropped() {
        assertThat(HopPath.countryOrNull("МГУ")).isNull()
        assertThat(HopPath.countryOrNull("ru")).isEqualTo("RU")
        val line = HopPath.line(
            "телефон",
            listOf(HopStop("203.0.113.5", country = "МГУ", timedOut = false)),
            "страна неизвестна",
            "таймаут",
        )
        assertThat(line).isEqualTo(
            """
            телефон
            1  203.0.113.5  страна неизвестна
            """.trimIndent(),
        )
        assertThat(line).doesNotContain("МГУ")
    }

    @Test
    fun ttlExceededKeepsTheRouterAndContinues() {
        val hop = parsePingHop(
            """
            PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
            From 10.0.2.2 icmp_seq=1 Time to live exceeded
            """.trimIndent(),
        )
        assertThat(hop.terminal).isFalse()
        assertThat(hop.stop.address).isEqualTo("10.0.2.2")
        assertThat(hop.stop.timedOut).isFalse()
        assertThat(hop.stop.country).isNull()
    }

    @Test
    fun bytesFromMarksTheDestination() {
        val hop = parsePingHop(
            """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            64 bytes from 1.1.1.1: icmp_seq=1 ttl=255 time=10.3 ms
            """.trimIndent(),
        )
        assertThat(hop.terminal).isTrue()
        assertThat(hop.stop.address).isEqualTo("1.1.1.1")
        assertThat(hop.stop.timedOut).isFalse()
    }

    @Test
    fun unreachableStopsAtTheReplyingAddress() {
        val hop = parsePingHop(
            """
            PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
            From 10.0.2.2: icmp_seq=1 Destination Host Unreachable
            1 packets transmitted, 0 received, +1 errors, 100% packet loss
            """.trimIndent(),
        )
        assertThat(hop.terminal).isTrue()
        assertThat(hop.stop.address).isEqualTo("10.0.2.2")
        assertThat(hop.stop.timedOut).isFalse()
    }

    @Test
    fun chipsKeepTimeoutGapsAndAppendSyntheticDestinationCountry() {
        val stops = listOf(
            HopStop("10.0.0.1", country = "ru", timedOut = false),
            HopStop(null, country = null, timedOut = true),
            HopStop(null, country = null, timedOut = true),
            HopStop("149.11.182.154", country = null, timedOut = false),
            HopStop("203.0.113.42", country = "de", timedOut = false, synthetic = true),
        )
        assertThat(HopPath.chips(stops)).containsExactly(
            HopChip.Phone,
            HopChip.Country("RU"),
            HopChip.Timeout,
            HopChip.Timeout,
            HopChip.Opaque,
            HopChip.Country("DE"),
            HopChip.TcpDestination,
        ).inOrder()
        assertThat(HopPath.chips(stops, awaiting = true).last()).isEqualTo(HopChip.Awaiting)
        assertThat(HopPath.silence(stops, channelUp = true)).isEqualTo(HopSilence.PARTIAL)
    }

    @Test
    fun cardsStayHonestOnTimeoutAndKnownTitleOnly() {
        val stops = listOf(
            HopStop(
                "10.0.0.1",
                country = "ru",
                timedOut = false,
                rttMs = 1,
                name = "gw.example.net",
                asn = 12389,
                org = "ROSTELECOM",
            ),
            HopStop(null, country = null, timedOut = true),
            HopStop("203.0.113.9", country = "МГУ", timedOut = false),
        )
        val cards = HopPath.cards(stops)
        assertThat(cards).hasSize(3)
        assertThat(cards[0].index).isEqualTo(1)
        assertThat(cards[0].title).isEqualTo("gw.example.net")
        assertThat(cards[0].country).isEqualTo("RU")
        assertThat(cards[0].rttMs).isEqualTo(1)
        assertThat(cards[0].address).isEqualTo("10.0.0.1")
        assertThat(cards[1].timedOut).isTrue()
        assertThat(cards[1].title).isNull()
        assertThat(cards[1].address).isNull()
        assertThat(cards[1].rttMs).isNull()
        assertThat(cards[2].country).isNull()
        assertThat(cards[2].title).isNull()
        assertThat(cards[2].address).isEqualTo("203.0.113.9")
        assertThat(HopPath.displayTitle(stops[0])).isEqualTo("gw.example.net")
        assertThat(HopPath.displayTitle(stops[2])).isNull()
        assertThat(HopPath.flagEmoji("de")).isEqualTo("🇩🇪")
        assertThat(HopPath.flagEmoji("МГУ")).isNull()
    }

    @Test
    fun fullySilentWalkWithTcpShowsAllSilenceAndDestinationChip() {
        val timeouts = List(3) { HopStop(null, country = null, timedOut = true) }
        val dest = HopStop("203.0.113.9", country = "nl", timedOut = false, asn = 64500, org = "EXAMPLE", synthetic = true)
        val stops = timeouts + dest
        assertThat(HopPath.silence(stops, channelUp = true)).isEqualTo(HopSilence.ALL)
        assertThat(HopPath.chips(stops)).containsExactly(
            HopChip.Phone,
            HopChip.Timeout,
            HopChip.Timeout,
            HopChip.Timeout,
            HopChip.Country("NL"),
            HopChip.TcpDestination,
        ).inOrder()
        val card = HopPath.cards(stops).last()
        assertThat(card.synthetic).isTrue()
        assertThat(card.title).isEqualTo("AS64500 EXAMPLE")
        assertThat(card.country).isEqualTo("NL")
    }

    @Test
    fun packetLossWithNoAddressIsATimeout() {
        val hop = parsePingHop(
            """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            1 packets transmitted, 0 received, 100% packet loss, time 0ms
            """.trimIndent(),
        )
        assertThat(hop.terminal).isFalse()
        assertThat(hop.stop.address).isNull()
        assertThat(hop.stop.timedOut).isTrue()
    }

    @Test
    fun midPathTimeoutsContinueLikeWindowsTracertThenAppendDestWhenAllSilent() = runTest {
        val calls = mutableListOf<Int>()
        val tracer = ShellPingTracer(
            ping = { ttl, _ ->
                calls += ttl
                "1 packets transmitted, 0 received, 100% packet loss"
            },
            maxHops = 6,
            annotate = { ip ->
                if (ip == "203.0.113.9") AsnFact("nl", 64500, "EXAMPLE") else null
            },
        )
        val stops = tracer.trace(OutboundEndpoint("203.0.113.9", 443), dialOk = true)
        assertThat(calls).hasSize(6)
        assertThat(calls).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        assertThat(stops).hasSize(7)
        assertThat(stops.take(6).all { it.timedOut && !it.synthetic }).isTrue()
        assertThat(stops.last().address).isEqualTo("203.0.113.9")
        assertThat(stops.last().synthetic).isTrue()
        assertThat(stops.last().country).isEqualTo("NL")
        assertThat(stops.last().asn).isEqualTo(64500)
        assertThat(HopPath.PING_WAIT_SEC).isEqualTo(5)
    }

    @Test
    fun partialIcmpPathStillShowsVerifiedTcpDestination() = runTest {
        val tracer = ShellPingTracer(
            ping = { ttl, _ ->
                if (ttl == 1) {
                    "From 10.0.0.1 icmp_seq=1 Time to live exceeded"
                } else {
                    "1 packets transmitted, 0 received, 100% packet loss"
                }
            },
            maxHops = 3,
        )
        val stops = tracer.trace(OutboundEndpoint("203.0.113.9", 443), dialOk = true)
        assertThat(stops).hasSize(4)
        assertThat(stops.first().address).isEqualTo("10.0.0.1")
        assertThat(stops.last().synthetic).isTrue()
        assertThat(HopPath.chips(stops).last()).isEqualTo(HopChip.TcpDestination)
    }

    @Test
    fun walkerStopsAfterTheDestinationAndSkipsLaterTtls() = runTest {
        val calls = mutableListOf<Int>()
        val tracer = ShellPingTracer(
            ping = { ttl, _ ->
                calls += ttl
                if (ttl == 1) "From 10.0.0.1 icmp_seq=1 Time to live exceeded" else "64 bytes from 1.1.1.1:"
            },
            maxHops = 6,
        )
        val stops = tracer.trace(OutboundEndpoint("1.1.1.1", 443), dialOk = true)
        assertThat(calls).containsExactly(1, 2).inOrder()
        assertThat(stops.map { it.address }).containsExactly("10.0.0.1", "1.1.1.1").inOrder()
    }

    @Test
    fun answeredHopPublishesCountryThenNetworkDetails() = runTest {
        val updates = mutableListOf<List<HopStop>>()
        val tracer = ShellPingTracer(
            ping = { _, _ -> "64 bytes from 8.8.8.8: icmp_seq=1 ttl=55 time=10.2 ms" },
            maxHops = 2,
            annotate = { AsnFact("US", null, null) },
            details = { HopNetworkDetails(15169, "GOOGLE", "8.8.8.0/24", "dns.google", "Google LLC") },
        )
        val stops = tracer.traceLive(OutboundEndpoint("8.8.8.8", 443), { true }) { updates += it }
        assertThat(stops.single().country).isEqualTo("US")
        assertThat(stops.single().asn).isEqualTo(15169)
        assertThat(stops.single().org).isEqualTo("GOOGLE")
        assertThat(stops.single().prefix).isEqualTo("8.8.8.0/24")
        assertThat(stops.single().name).isEqualTo("dns.google")
        assertThat(stops.single().registeredTo).isEqualTo("Google LLC")
        assertThat(updates.first().single().country).isEqualTo("US")
        assertThat(HopPath.cards(stops).single().prefix).isEqualTo("8.8.8.0/24")
    }

    @Test
    fun aHostThatLooksLikeAFlagIsNotPassedToPing() = runTest {
        var calls = 0
        val tracer = ShellPingTracer(
            ping = { _, _ ->
                calls += 1
                "64 bytes from 1.1.1.1:"
            },
        )
        val stops = tracer.trace(OutboundEndpoint("-n", 1), dialOk = false)
        assertThat(calls).isEqualTo(0)
        assertThat(stops.single().address).isEqualTo("-n")
        assertThat(stops.single().timedOut).isTrue()
        assertThat(stops.single().country).isNull()
    }

    @Test
    fun timeoutsKeepWalkingUntilMaxHops() = runTest {
        var calls = 0
        val tracer = ShellPingTracer(
            ping = { _, _ ->
                calls += 1
                "100% packet loss"
            },
            maxHops = HopPath.MAX_HOPS,
        )
        val stops = tracer.trace(OutboundEndpoint("203.0.113.5", 443), dialOk = false)
        assertThat(calls).isEqualTo(HopPath.MAX_HOPS)
        assertThat(stops).hasSize(HopPath.MAX_HOPS)
        assertThat(stops.all { it.timedOut && !it.synthetic }).isTrue()
    }

    @Test
    fun liveTracePublishesEachHopIncludingMidPathTimeouts() = runTest {
        val partials = mutableListOf<List<Boolean>>()
        val tracer = ShellPingTracer(
            ping = { ttl, _ ->
                when (ttl) {
                    1 -> "From 10.0.0.1 icmp_seq=1 Time to live exceeded"
                    2, 3 -> "100% packet loss"
                    else -> "64 bytes from 1.1.1.1: time=12.0 ms"
                }
            },
            maxHops = 6,
        )
        val stops = tracer.traceLive(OutboundEndpoint("1.1.1.1", 443), dialOk = { true }) { rows ->
            partials += rows.map { it.timedOut }
        }
        assertThat(partials).containsExactly(
            listOf(false),
            listOf(false, true),
            listOf(false, true, true),
            listOf(false, true, true, false),
        ).inOrder()
        assertThat(stops.map { it.address }).containsExactly("10.0.0.1", null, null, "1.1.1.1").inOrder()
        assertThat(stops.last().rttMs).isEqualTo(12)
    }

    @Test
    fun annotateFillsCountryAsnAndOrgFromTheHopAddress() = runTest {
        val tracer = ShellPingTracer(
            ping = { _, _ -> "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117" },
            annotate = { ip ->
                if (ip == "8.8.8.8") AsnFact("us", 15169, "GOOGLE") else null
            },
        )
        val hop = tracer.trace(OutboundEndpoint("8.8.8.8", 443), dialOk = true).single()
        assertThat(hop.country).isEqualTo("US")
        assertThat(hop.asn).isEqualTo(15169)
        assertThat(hop.org).isEqualTo("GOOGLE")
    }

    @Test
    fun aRegistryMissLeavesCountryAsnAndOrgEmpty() = runTest {
        val seen = mutableListOf<String>()
        val tracer = ShellPingTracer(
            ping = { ttl, _ ->
                if (ttl == 1) "From 203.0.113.9 icmp_seq=1 Time to live exceeded" else "64 bytes from 198.51.100.2:"
            },
            annotate = { ip ->
                seen += ip
                AsnFact(country = "МГУ", asn = 0, org = "  ")
            },
        )
        val stops = tracer.trace(OutboundEndpoint("198.51.100.2", 443), dialOk = true)
        assertThat(seen).containsExactly("203.0.113.9", "198.51.100.2").inOrder()
        assertThat(stops).hasSize(2)
        assertThat(stops.all { it.country == null && it.asn == null && it.org == null }).isTrue()
    }
}
