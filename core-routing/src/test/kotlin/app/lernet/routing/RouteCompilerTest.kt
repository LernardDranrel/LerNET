package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RouteCompilerTest {
    @Test
    fun exactDomainBeatsApp() {
        val domain = node("d", RuleMatch(domains = listOf("api.example.com")), RouteAction.DIRECT, sort = 1)
        val app = node("a", RuleMatch(apps = listOf("com.foo.bar")), RouteAction.BLOCK, sort = 0)
        val compiled = compile(listOf(app, domain))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("a", "d").inOrder()
    }

    @Test
    fun exactDomainBeatsSuffix() {
        val exact = node("e", RuleMatch(domains = listOf("www.example.com")), RouteAction.PROXY)
        val suffix = node("s", RuleMatch(domainSuffixes = listOf("example.com")), RouteAction.DIRECT)
        val compiled = compile(listOf(suffix, exact))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("e", "s").inOrder()
    }

    @Test
    fun suffixBeatsCidr() {
        val suffix = node("s", RuleMatch(domainSuffixes = listOf("example.com")), RouteAction.PROXY)
        val cidr = node("c", RuleMatch(ipCidrs = listOf("1.2.3.0/24")), RouteAction.DIRECT)
        val compiled = compile(listOf(cidr, suffix))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("c", "s").inOrder()
    }

    @Test
    fun longerCidrBeatsShorter() {
        val long = node("long", RuleMatch(ipCidrs = listOf("10.0.0.0/24")), RouteAction.DIRECT, sort = 5)
        val short = node("short", RuleMatch(ipCidrs = listOf("10.0.0.0/8")), RouteAction.PROXY, sort = 0)
        val compiled = compile(listOf(short, long))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("short", "long").inOrder()
    }

    @Test
    fun cidrBeatsGeoip() {
        val cidr = node("c", RuleMatch(ipCidrs = listOf("8.8.8.8/32")), RouteAction.DIRECT)
        val geo = node("g", RuleMatch(geoip = listOf("us")), RouteAction.PROXY)
        val compiled = compile(listOf(geo, cidr))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("c", "g").inOrder()
    }

    @Test
    fun geoipBeatsApp() {
        val geo = node("g", RuleMatch(geoip = listOf("ru")), RouteAction.DIRECT)
        val app = node("a", RuleMatch(apps = listOf("org.app.client")), RouteAction.PROXY)
        val compiled = compile(listOf(app, geo))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("a", "g").inOrder()
    }

    @Test
    fun appBeatsCatchAll() {
        val app = node("a", RuleMatch(apps = listOf("org.app.client")), RouteAction.DIRECT)
        val catchAll = node("z", RuleMatch(), RouteAction.BLOCK)
        val compiled = RouteCompiler.compile(listOf(catchAll, app))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("a")
        assertThat(compiled.finalAction).isEqualTo(RouteAction.BLOCK)
    }

    @Test
    fun equalSpecificityUsesTreeOrder() {
        val first = node("first", RuleMatch(domains = listOf("a.example")), RouteAction.DIRECT, sort = 0)
        val second = node("second", RuleMatch(domains = listOf("b.example")), RouteAction.BLOCK, sort = 1)
        val compiled = compile(listOf(second, first))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun mixedAndUsesHighestKind() {
        val mixed = node(
            "m",
            RuleMatch(apps = listOf("com.only.app"), domains = listOf("only.example.com")),
            RouteAction.DIRECT,
        )
        val app = node("a", RuleMatch(apps = listOf("com.only.app")), RouteAction.PROXY)
        val compiled = compile(listOf(app, mixed))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("a", "m").inOrder()
        assertThat(compiled.rules.first { it.nodeId == "m" }.specificity.kind).isEqualTo(MatchKind.EXACT_DOMAIN)
    }

    @Test
    fun invalidCidrFailsClosed() {
        val bad = node("bad", RuleMatch(ipCidrs = listOf("10.0.0.1")), RouteAction.DIRECT)
        val compiled = RouteCompiler.compile(listOf(bad))
        assertThat(compiled.isValid).isFalse()
        assertThat(compiled.errors.any { it.field == "ip_cidr" }).isTrue()
    }

    @Test
    fun orphanWithoutChildrenIsNotCompiled() {
        val keep = node("keep", RuleMatch(domains = listOf("keep.example")), RouteAction.PROXY)
        val loose = node("loose", RuleMatch(domains = listOf("loose.example")), RouteAction.DIRECT)
            .copy(parentId = RouteTree.ORPHAN)
        val compiled = compile(listOf(loose, keep))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("keep")
    }

    @Test
    fun orphanBranchIsNotCompiled() {
        val keep = node("keep", RuleMatch(domains = listOf("keep.example")), RouteAction.PROXY)
        val loose = node("loose", RuleMatch(domains = listOf("loose.example")), RouteAction.DIRECT)
            .copy(parentId = RouteTree.ORPHAN)
        val child = node("child", RuleMatch(domains = listOf("child.example")), RouteAction.BLOCK)
            .copy(parentId = "loose")
        val compiled = compile(listOf(loose, child, keep))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("keep")
    }

    @Test
    fun childOfANamedPipeFailsClosed() {
        val named = node("p", RuleMatch(domains = listOf("parent.example")), RouteAction.PROXY).copy(pipeName = "video")
        val underNamed = node("c", RuleMatch(domains = listOf("child.example")), RouteAction.DIRECT).copy(parentId = "p")
        val namedCompiled = RouteCompiler.compile(listOf(named, underNamed))
        assertThat(namedCompiled.errors).containsExactly(FieldError("c", "parent", RouteElse.PIPE_AND_FORK))
    }

    @Test
    fun everyActionCanBranchAndOnlyDeepTerminalActionsAreEmitted() {
        RouteAction.entries.forEach { action ->
            val parent = node("p", RuleMatch(domains = listOf("parent.example")), action)
            val branch = node("b", RuleMatch(apps = listOf("com.example.app")), RouteAction.BLOCK).copy(parentId = "p")
            val leaf = node("c", RuleMatch(ipCidrs = listOf("10.0.0.0/8")), RouteAction.DIRECT).copy(parentId = "b")
            val innerElse = node("be", RuleMatch(), RouteAction.PROXY, sort = 1).copy(parentId = "b")
            val outerElse = node("pe", RuleMatch(), RouteAction.BLOCK, sort = 1).copy(parentId = "p")
            val compiled = compile(listOf(parent, branch, leaf, innerElse, outerElse))
            assertThat(compiled.isValid).isTrue()
            assertThat(compiled.rules.map { it.nodeId }).containsExactly("c", "be", "pe").inOrder()
            val first = RouteCompiler.toSingBoxRules(compiled, "vpn").first().toString()
            assertThat(first).contains("parent.example")
            assertThat(first).contains("com.example.app")
            assertThat(first).contains("10.0.0.0/8")
            assertThat(first).contains("\"outbound\":\"direct\"")
        }
    }

    @Test
    fun disabledForkCannotPromoteItsEnabledChildrenToRoot() {
        val parent = node("p", RuleMatch(domains = listOf("parent.example")), RouteAction.DIRECT).copy(enabled = false)
        val child = node("c", RuleMatch(), RouteAction.BLOCK).copy(parentId = "p")
        val compiled = compile(listOf(parent, child))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules).isEmpty()
        assertThat(compiled.finalAction).isEqualTo(RouteAction.PROXY)
    }

    @Test
    fun everyDepthRequiresAnEnabledTerminalFallback() {
        val parent = node("p", RuleMatch(domains = listOf("parent.example")), RouteAction.DIRECT)
        val child = node("c", RuleMatch(domains = listOf("child.example")), RouteAction.BLOCK).copy(parentId = "p")
        assertThat(compile(listOf(parent, child)).errors.map { it.message }).contains(RouteElse.MISSING)
        val disabledElse = node("pe", RuleMatch(), RouteAction.PROXY, sort = 1).copy(parentId = "p", enabled = false)
        assertThat(compile(listOf(parent, child, disabledElse)).errors.map { it.message }).contains(RouteElse.DISABLED)
    }

    @Test
    fun forkAndsAncestorConditionsAndKeepsLeafOrder() {
        val parent = node("p", RuleMatch(domains = listOf("parent.example")), RouteAction.PROXY)
        val child = node("c", RuleMatch(domains = listOf("child.example")), RouteAction.DIRECT).copy(parentId = "p")
        val inner = node("ie", RuleMatch(), RouteAction.BLOCK, sort = 1).copy(parentId = "p")
        val compiled = compile(listOf(parent, child, inner))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("c", "ie").inOrder()
        val rules = RouteCompiler.toSingBoxRules(compiled, "proxy")
        assertThat(rules[0]["outbound"]!!.jsonPrimitive.content).isEqualTo("direct")
        assertThat(rules[0].toString()).contains("parent.example")
        assertThat(rules[0].toString()).contains("child.example")
        assertThat(rules[1].toString()).contains("parent.example")
        assertThat(rules[1].containsKey("action")).isTrue()
    }

    @Test
    fun missingElseRefusesSave() {
        val compiled = RouteCompiler.compile(listOf(node("d", RuleMatch(domains = listOf("a.example")), RouteAction.DIRECT)))
        assertThat(compiled.isValid).isFalse()
        assertThat(compiled.errors.map { it.field }).contains("else")
    }

    @Test
    fun negatedGeoipAndPrivatePassValidation() {
        val geo = node("g", RuleMatch(geoip = listOf("!ru", "private")), RouteAction.DIRECT)
        val compiled = compile(listOf(geo))
        assertThat(compiled.isValid).isTrue()
        val rule = RouteCompiler.toSingBoxRules(compiled, "proxy").single()
        assertThat(rule.toString()).contains("geoip-ru")
        assertThat(rule.toString()).contains("ip_is_private")
        assertThat(rule.toString()).doesNotContain("\"geoip\"")
    }

    @Test
    fun negatedProcessPassesValidationAndUsesInvert() {
        val process = node("p", RuleMatch(processes = listOf("!browser.exe")), RouteAction.DIRECT)
        val compiled = compile(listOf(process))
        assertThat(compiled.isValid).isTrue()
        val rule = RouteCompiler.toSingBoxRules(compiled, "proxy").single().toString()
        assertThat(rule).contains("browser.exe")
        assertThat(rule).contains("\"invert\":true")
    }

    @Test
    fun unknownCountryCodeFailsClosed() {
        val geo = node("g", RuleMatch(geoip = listOf("zz")), RouteAction.DIRECT)
        val compiled = compile(listOf(geo))
        assertThat(compiled.isValid).isFalse()
        assertThat(compiled.errors.any { it.field == "geoip" }).isTrue()
    }

    @Test
    fun disabledNodesAreIgnored() {
        val disabled = node("x", RuleMatch(domains = listOf("skip.example")), RouteAction.BLOCK).copy(enabled = false)
        val keep = node("y", RuleMatch(domains = listOf("keep.example")), RouteAction.DIRECT)
        val compiled = compile(listOf(disabled, keep))
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("y")
    }

    @Test
    fun otherwiseCanBranchRepeatedlyAndOnlyTerminalOutcomesAreEmitted() {
        val first = node("first", RuleMatch(domains = listOf("first.example")), RouteAction.DIRECT)
        val outerElse = node("outer-else", RuleMatch(), RouteAction.BLOCK, sort = 1)
        val second = node("second", RuleMatch(domains = listOf("second.example")), RouteAction.PROXY)
            .copy(parentId = outerElse.id)
        val innerElse = node("inner-else", RuleMatch(), RouteAction.DIRECT, sort = 1)
            .copy(parentId = outerElse.id)
        val third = node("third", RuleMatch(domains = listOf("third.example")), RouteAction.BLOCK)
            .copy(parentId = innerElse.id)
        val terminal = node("terminal", RuleMatch(), RouteAction.PROXY, sort = 1)
            .copy(parentId = innerElse.id, pipeName = "video")
        val nodes = listOf(first, outerElse, second, innerElse, third, terminal)
        val compiled = RouteCompiler.compile(nodes)
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules.map { it.nodeId }).containsExactly("first", "second", "third", "terminal").inOrder()
        val rules = RouteCompiler.toSingBoxRules(compiled, "vpn", mapOf("video" to "video-out"))
        assertThat(rules.last()["outbound"]!!.jsonPrimitive.content).isEqualTo("video-out")
        assertThat(rules[2]["action"]!!.jsonPrimitive.content).isEqualTo("reject")
        assertThat(RouteCompiler.compile(nodes.filterNot { it.id == terminal.id }).errors.map { it.message })
            .contains(RouteElse.MISSING)
        assertThat(RouteCompiler.compile(nodes.map { if (it.id == innerElse.id) it.copy(enabled = false) else it })
            .errors.map { it.message }).contains(RouteElse.DISABLED)
    }

    private fun compile(nodes: List<RuleNode>): CompiledRoute =
        RouteCompiler.compile(nodes + node("else", RuleMatch(), RouteAction.PROXY, sort = 100))

    private fun node(
        id: String,
        match: RuleMatch,
        action: RouteAction,
        sort: Int = 0,
    ): RuleNode = RuleNode(
        id = id,
        parentId = null,
        enabled = true,
        sortIndex = sort,
        match = match,
        action = action,
    )
}
