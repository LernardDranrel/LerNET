package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ConditionJsonTest {
    @Test
    fun negatedDomainMaskSurvivesProjectionRoundTrip() {
        val patterns = listOf("!*.example.com", "plain.example.com")
        val (domains, suffixes) = DomainPatterns.split(patterns)
        assertThat(suffixes).containsExactly("!example.com")
        assertThat(DomainPatterns.join(domains, suffixes)).containsExactlyElementsIn(patterns)
    }

    @Test
    fun privateNetworksHaveOwnBlockAndKeepSingBoxSemantics() {
        val match = RuleMatch(emptyList(), emptyList(), emptyList(), emptyList(), listOf("private"))
        val decoded = ConditionCodec.fromMatch(match)
        assertThat(decoded.blocks.map { it.kind }).containsExactly(ConditionKind.PRIVATE)
        assertThat(ConditionCodec.project(decoded).geoip).containsExactly("private")
        assertThat(
            emit(RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.PRIVATE, listOf("private")))))
                .toString()
        ).contains("ip_is_private")
        val legacy = ConditionCodec.fromMatch(match.copy(geoip = listOf("ru", "private")))
        assertThat(legacy.blocks).containsExactly(ConditionBlock(ConditionKind.GEOIP, listOf("ru", "private")))
    }

    @Test
    fun domainBlockOrsExactAndGlobs() {
        val conditions = RuleConditions(
            MatchJoin.OR,
            listOf(
                ConditionBlock(
                    ConditionKind.DOMAIN,
                    listOf("api.openai.com", "*.openai.com", "*.chatgpt.com"),
                ),
            ),
        )
        val rule = emit(conditions)
        assertThat(rule["type"]!!.jsonPrimitive.content).isEqualTo("logical")
        assertThat(rule["mode"]!!.jsonPrimitive.content).isEqualTo("or")
        val inners = rule["rules"] as JsonArray
        assertThat(inners.toString()).contains("api.openai.com")
        assertThat(inners.toString()).contains("openai.com")
        assertThat(inners.toString()).contains("chatgpt.com")
        assertThat(rule["outbound"]!!.jsonPrimitive.content).isEqualTo("proxy")
    }

    @Test
    fun blocksDefaultToOneLogicalOr() {
        val conditions = RuleConditions(
            MatchJoin.OR,
            listOf(
                ConditionBlock(ConditionKind.DOMAIN, listOf("*.openai.com", "*.chatgpt.com")),
                ConditionBlock(ConditionKind.GEOIP, listOf("!ru", "!kz")),
            ),
        )
        val compiled = compile(conditions)
        assertThat(compiled.rules).hasSize(1)
        val rule = RouteCompiler.toSingBoxRules(compiled, "proxy").single()
        assertThat(rule["mode"]!!.jsonPrimitive.content).isEqualTo("or")
        val inners = rule["rules"] as JsonArray
        assertThat(inners[0].toString()).contains("openai.com")
        assertThat(inners[0].toString()).contains("chatgpt.com")
        assertThat(inners[1].toString()).contains("geoip-ru")
        assertThat(inners[1].toString()).contains("geoip-kz")
        assertThat(inners[1].toString()).contains("\"invert\":true")
        assertThat(inners[1].toString()).doesNotContain("!ru")
    }

    @Test
    fun positiveCountriesShareOneRuleSet() {
        val rule = emit(
            RuleConditions(MatchJoin.OR, listOf(ConditionBlock(ConditionKind.GEOIP, listOf("ru", "by")))),
        )
        assertThat(rule.containsKey("type")).isFalse()
        assertThat(rule.containsKey("invert")).isFalse()
        assertThat(rule["rule_set"].toString()).contains("geoip-ru")
        assertThat(rule["rule_set"].toString()).contains("geoip-by")
    }

    @Test
    fun negatedCountryStaysInvertedBesideADomain() {
        val rule = emit(
            RuleConditions(
                MatchJoin.AND,
                listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("*.openai.com")),
                    ConditionBlock(ConditionKind.GEOIP, listOf("!ru")),
                ),
            ),
        )
        assertThat(rule["mode"]!!.jsonPrimitive.content).isEqualTo("and")
        assertThat(rule.containsKey("invert")).isFalse()
        val geo = (rule["rules"] as JsonArray).first { it.toString().contains("geoip-ru") }
        assertThat(geo.toString()).contains("\"invert\":true")
        assertThat(geo.toString()).doesNotContain("openai.com")
    }

    @Test
    fun negatedDomainInvertsOnlyThatPattern() {
        val rule = emit(
            RuleConditions(
                MatchJoin.OR,
                listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("!*.evil.com", "keep.example"))),
            ),
        )
        assertThat(rule["mode"]!!.jsonPrimitive.content).isEqualTo("or")
        val dumped = rule.toString()
        assertThat(dumped).contains("evil.com")
        assertThat(dumped).contains("keep.example")
        assertThat(dumped).contains("\"invert\":true")
        assertThat(dumped).doesNotContain("!*.evil.com")
    }

    @Test
    fun negatedAppAndCidrUseInvert() {
        val rule = emit(
            RuleConditions(
                MatchJoin.OR,
                listOf(
                    ConditionBlock(ConditionKind.APP, listOf("!com.example.app")),
                    ConditionBlock(ConditionKind.CIDR, listOf("!10.1.0.0/16")),
                ),
            ),
        )
        val dumped = rule.toString()
        assertThat(dumped).contains("com.example.app")
        assertThat(dumped).contains("10.1.0.0/16")
        assertThat(dumped).contains("\"invert\":true")
        assertThat(dumped).doesNotContain("!com.example.app")
    }

    @Test
    fun andMergesDomainAndApp() {
        val conditions = RuleConditions(
            MatchJoin.AND,
            listOf(
                ConditionBlock(ConditionKind.DOMAIN, listOf("*.openai.com")),
                ConditionBlock(ConditionKind.APP, listOf("com.openai.chat")),
            ),
        )
        val rule = emit(conditions)
        assertThat(rule.containsKey("type")).isFalse()
        assertThat(rule["domain_suffix"].toString()).contains("openai.com")
        assertThat(rule["package_name"].toString()).contains("com.openai.chat")
    }

    @Test
    fun andOfTwoDomainBlocksStaysLogical() {
        val conditions = RuleConditions(
            MatchJoin.AND,
            listOf(
                ConditionBlock(ConditionKind.DOMAIN, listOf("*.openai.com")),
                ConditionBlock(ConditionKind.DOMAIN, listOf("*.chatgpt.com")),
            ),
        )
        val rule = emit(conditions)
        assertThat(rule["type"]!!.jsonPrimitive.content).isEqualTo("logical")
        assertThat(rule["mode"]!!.jsonPrimitive.content).isEqualTo("and")
    }

    @Test
    fun legacyFlatMatchIsUnchanged() {
        val node = RuleNode(
            id = "n",
            parentId = null,
            enabled = true,
            sortIndex = 0,
            match = RuleMatch(domains = listOf("a.example")),
            action = RouteAction.DIRECT,
        )
        val rest = node.copy(id = "else", sortIndex = 1, match = RuleMatch(), action = RouteAction.PROXY)
        val rule = RouteCompiler.toSingBoxRules(RouteCompiler.compile(listOf(node, rest)), "proxy").single()
        assertThat(rule.containsKey("type")).isFalse()
        assertThat(rule["domain"].toString()).contains("a.example")
        assertThat(rule["outbound"]!!.jsonPrimitive.content).isEqualTo("direct")
    }

    @Test
    fun blankBlocksRoundTripAsCatchAll() {
        val conditions = RuleConditions(MatchJoin.OR, listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(""))))
        val projected = ConditionCodec.project(conditions)
        assertThat(projected.isCatchAll()).isTrue()
        val decoded = ConditionCodec.decode(ConditionCodec.encode(conditions), RuleMatch())
        assertThat(decoded.join).isEqualTo(MatchJoin.OR)
        assertThat(decoded.blocks).hasSize(1)
    }

    @Test
    fun legacyMixedKindsStayAnd() {
        val match = RuleMatch(domains = listOf("youtube.com"), apps = listOf("com.google.android.youtube"))
        val decoded = ConditionCodec.fromMatch(match)
        assertThat(decoded.join).isEqualTo(MatchJoin.AND)
        assertThat(decoded.blocks.map { it.kind }).containsExactly(ConditionKind.DOMAIN, ConditionKind.APP).inOrder()
    }

    private fun emit(conditions: RuleConditions) =
        RouteCompiler.toSingBoxRules(compile(conditions), "proxy").single()

    private fun compile(conditions: RuleConditions) = RouteCompiler.compile(
        listOf(
            RuleNode(
                id = "n",
                parentId = null,
                enabled = true,
                sortIndex = 0,
                match = ConditionCodec.project(conditions),
                action = RouteAction.PROXY,
                conditions = conditions,
            ),
            RuleNode(
                id = "else",
                parentId = null,
                enabled = true,
                sortIndex = 1,
                match = RuleMatch(),
                action = RouteAction.PROXY,
            ),
        ),
    )
}
