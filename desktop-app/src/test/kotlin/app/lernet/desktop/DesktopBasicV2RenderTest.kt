package app.lernet.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.lernet.engine.text.SettingsWords
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Mockup 03: rules, journal and settings open from the top; «Дополнительно» in settings is folded. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopBasicV2RenderTest {
    @Test
    fun `top buttons open settings and advanced knobs stay folded until asked`() {
        val controller = DesktopController(DesktopStore(Files.createTempDirectory("lernet-basic-v2")))
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    DesktopScreen(controller, {}, {}, topChrome = {
                        DesktopModeTabs(false, {}, reducedMotion = true)
                    })
                }
            }
        }
        try {
            screenshot(scene, "windows-basic-v2-home")
            listOf("Правила", "Журнал запросов", "Настройки", "Анализ сети ↗").forEach { word ->
                assertThat(nodes(scene).any { texts(it).any { t -> t == word } }).isTrue()
            }
            click(scene, "Настройки")
            screenshot(scene, "windows-basic-v2-settings")
            assertThat(nodes(scene).any { SettingsWords.MTU_TITLE in texts(it) }).isFalse()
            // S1: the advanced knobs are a separate «Для опытных» page.
            click(scene, "Для опытных")
            screenshot(scene, "windows-basic-v2-settings-advanced")
            assertThat(nodes(scene).any { SettingsWords.MTU_TITLE in texts(it) }).isTrue()
        } finally {
            scene.close()
            controller.close()
        }
    }

    @Test
    fun `VPN rules open the branch map and edit only the selected owners legacy rule`() {
        val folder = "grp_work"
        val source = StoredRule("sso", folder, "work", 0, title = "Единый вход", action = "PROXY",
            domains = listOf("login.office.example"), pipeName = "Офис")
        val other = source.copy(id = "other", profileId = "home", parentId = null)
        val rules = listOf(
            StoredRule("work", folder, sortIndex = 0, title = "Рабочие сервисы", domainSuffixes = listOf("office.example")),
            source, StoredRule("work-else", folder, "work", 1, title = "Иначе", action = "DIRECT"),
            StoredRule("local", folder, sortIndex = 1, title = "Локальная сеть", cidrs = listOf("10.0.0.0/8"), action = "DIRECT"),
            StoredRule("else", folder, sortIndex = 2, title = "Иначе"), other,
        )
        val dir = Files.createTempDirectory("lernet-vpn-branches")
        val store = DesktopStore(dir)
        val outbound = StoredOutbound("socks", "proxy", "socks", """{"type":"socks","server":"office.example","server_port":1080}""")
        store.save(StoredState(
            groups = listOf(StoredGroup("work", "Работа")),
            profiles = listOf(StoredProfile("office", "Офис", "JSON_PASTE", listOf(outbound), outbound.id, groupId = "work"),
                StoredProfile("home", "Дом", "JSON_PASTE", listOf(outbound.copy(id = "home-exit")), "home-exit")),
            rules = rules, selectedProfileId = "office",
        ))
        val controller = DesktopController(store)
        val scene = ImageComposeScene(1360, 860, Density(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
                MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                    Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                        DesktopScreen(controller, {}, {}, topChrome = { DesktopModeTabs(false, {}, reducedMotion = true) })
                    }
                }
            }
        }
        try {
            screenshot(scene, "vpn-branch-home")
            click(scene, "Правила")
            screenshot(scene, "vpn-branch-map")
            assertThat(nodes(scene).any { "Схема правил" in texts(it) }).isTrue()
            val fold = nodes(scene).single { "Раскрыть · Рабочие сервисы" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            fold.config[SemanticsActions.OnClick].action!!.invoke()
            screenshot(scene, "vpn-branch-expanded")
            val search = nodes(scene).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            search.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("Единый вход"))
            repeat(3) { scene.render(0).close() }
            val hit = nodes(scene).single { "Вся схема › Рабочие сервисы" in texts(it) && "Единый вход" in texts(it) }
            hit.config[SemanticsActions.OnClick].action!!.invoke()
            repeat(3) { scene.render(0).close() }
            assertThat(store.load().rules).isEqualTo(rules)
            click(scene, "Редактировать")
            val title = nodes(scene).first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == source.title }
            title.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("Вход в портал"))
            repeat(3) { scene.render(0).close() }
            click(scene, "В черновик · Ctrl+Enter")
            assertThat(store.load().rules).isEqualTo(rules)
            click(scene, "Сохранить")
            val reopened = DesktopStore(dir).load()
            assertThat(reopened.rules.first { it.id == source.id }).isEqualTo(source.copy(title = "Вход в портал"))
            assertThat(reopened.rules.filter { it.profileId == "home" }).containsExactly(other)
        } finally {
            scene.close()
            controller.close()
        }
    }

    @Test
    fun `VPN pins round trip without touching a channel another owner or routing order`() {
        val rule = StoredRule("root", "grp_work", sortIndex = 0, domains = listOf("old.example"))
        val other = StoredRule("b", "home", sortIndex = 0)
        val dir = Files.createTempDirectory("vpn-rule-pins")
        val store = DesktopStore(dir)
        store.save(StoredState(rules = listOf(rule, other),
            groups = listOf(StoredGroup("work", "Работа", canvasLayout = """{"pipe:Офис":{"x":15,"y":20}}""")),
            rulePositions = mapOf(rule.id to RulePosition(120f, 25f), other.id to RulePosition(5f, 6f))))
        val ctrl = DesktopController(store)
        try {
            val initial = ctrl.routeDraft(rule.profileId)
            val nodeKey = app.lernet.routing.policy.PolicyCanvasKeys.node(rule.id)
            val position = app.lernet.routing.policy.PolicyCanvasPoint(160f, 50f)
            val next = initial.copy(layout = initial.layout + (nodeKey to position) + ("root" to position.copy(x = 10f)))
            assertThat(ctrl.commitRuleDraft(rule.profileId, next)).isTrue()
            assertThat(ctrl.routeDraft(rule.profileId).layout).isEqualTo(next.layout)
            assertThat(ctrl.commitRuleDraft(rule.profileId, next.copy(layout = emptyMap()))).isFalse()
            val saved = ctrl.routeDraft(rule.profileId)
            assertThat(ctrl.commitRuleDraft(rule.profileId, saved.copy(layout = emptyMap()))).isTrue()
            val reopened = store.load()
            assertThat(reopened.groups.single().canvasLayout).isEqualTo("""{"pipe:Офис":{"x":15,"y":20}}""")
            assertThat(reopened.rulePositions).containsExactly(other.id, RulePosition(5f, 6f))
            assertThat(reopened.rules).containsExactly(rule, other)
        } finally { ctrl.close() }
    }

    private fun click(scene: ImageComposeScene, title: String) {
        fun owner(node: SemanticsNode?): SemanticsNode? = when {
            node == null -> null
            node.config.getOrNull(SemanticsActions.OnClick) != null -> node
            else -> owner(node.parent)
        }
        val node = owner(nodes(scene).first { title in texts(it) })!!
        node.config[SemanticsActions.OnClick].action!!.invoke()
        scene.render(0).close()
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        var t = 0L
        repeat(4) { scene.render(t).close(); t += 500_000_000L }
        scene.render(t).use { image ->
            val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            File("build/expert-ui-review").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
