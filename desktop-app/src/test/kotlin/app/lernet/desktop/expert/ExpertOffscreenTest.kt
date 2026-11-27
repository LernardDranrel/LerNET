package app.lernet.desktop.expert

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.desktop.desktopColors
import app.lernet.desktop.desktopTypography
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Renders pure Compose content to an image; no Window, controller, VPN or adapter is instantiated. */
@OptIn(ExperimentalComposeUiApi::class)
class ExpertOffscreenTest {
    @Test
    fun `deep path stays readable at entry and the explicit fit includes the last node`() {
        val rules = (1..60).map { level ->
            PolicyNode("r$level", parentId = if (level == 1) null else "r${level - 1}", title = "Уровень $level")
        }
        val branches = DesktopRuleBranches(PolicyTree(PolicyScope.Device, rules))
        assertThat(branches.path("r60").map { it.id }).containsExactlyElementsIn(rules.map { it.id }).inOrder()
        assertThat(branches.visible("r59", emptySet()).single().id).isEqualTo("r60")
        val size = androidx.compose.ui.unit.IntSize(760, 480)
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, 16800f, 62f)
        val first = androidx.compose.ui.geometry.Rect(0f, 0f, 216f, 62f)
        val last = first.translate(androidx.compose.ui.geometry.Offset(16584f, 0f))
        val map = app.lernet.desktop.RouteMapOverview(mapOf("root" to first, "r60" to last), emptyList(), "root", "r60", false)
        val entry = map.fit(bounds, size, 1f, readable = true)
        assertThat(entry.zoom).isAtLeast(.9f)
        assertThat(entry.project(last.center, 1f).x).isAtLeast(0f)
        assertThat(entry.project(last.center, 1f).x).isAtMost(size.width.toFloat())
        val whole = map.fit(bounds, size, 1f, readable = false)
        assertThat(whole.project(first.topLeft, 1f).x).isAtLeast(0f)
        assertThat(whole.project(last.bottomRight, 1f).x).isAtMost(size.width.toFloat())
    }

    @Test
    fun `desktop map starts at readable scale and search opens the correct nested rule`() {
        val domains = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("office.example"))))
        val rules = listOf(
            PolicyNode("work", title = "Работа", conditions = domains),
            PolicyNode("in", parentId = "work", title = "Единый вход", conditions = domains),
            PolicyNode("foreign", sortIndex = 1, title = "Локальная сеть", conditions = domains),
            PolicyNode("other", parentId = "foreign", title = "Единый вход", conditions = domains),
        )
        val intents = mutableListOf<ExpertIntent>()
        val state = ExpertUiState(NetworkPolicy(device = PolicyTree(PolicyScope.Device, rules)), reducedMotion = true)
        val scene = ImageComposeScene(1166, 724, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(state, intents::add, topChrome = { app.lernet.desktop.DesktopModeTabs(true, {}) })
            }
        }
        try {
            repeat(3) { scene.render(0).close() }
            click(scene, "Схема")
            val root = nodes(scene).first { "Начало схемы" in texts(it) }
            val branch = nodes(scene).first { "Работа" in texts(it) && "Вложенные правила · 1" in texts(it) }
            val fold = nodes(scene).first { "Раскрыть · Работа" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertThat(branch.boundsInRoot.left).isGreaterThan(root.boundsInRoot.right)
            // The branch label and its expand button are separately accessible parts of one card.
            assertThat(fold.boundsInRoot.right - branch.boundsInRoot.left).isAtLeast(190f)
            assertThat(nodes(scene).none { "Единый вход" in texts(it) }).isTrue()
            fold.config[SemanticsActions.OnClick].action!!.invoke()
            screenshot(scene, "map-expanded")
            assertThat(nodes(scene).any { "Единый вход" in texts(it) }).isTrue()
            val search = nodes(scene).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            search.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Единый вход"))
            repeat(3) { scene.render(0).close() }
            val hit = nodes(scene).single { "Вся схема › Локальная сеть" in texts(it) && "Единый вход" in texts(it) }
            hit.config[SemanticsActions.OnClick].action!!.invoke()
            screenshot(scene, "map-search-focus")
            assertThat(nodes(scene).any { "Редактировать" in texts(it) }).isTrue()
            assertThat(nodes(scene).none { "Работа" in texts(it) }).isTrue()
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `all desktop expert pages render without starting network`() {
        val intents = mutableListOf<ExpertIntent>()
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode(
                        "first", title = "Рабочие сайты",
                        conditions = RuleConditions(
                            blocks = listOf(
                                ConditionBlock(ConditionKind.DOMAIN, listOf("*.example.invalid")),
                            )
                        ),
                        target = PolicyTarget.Channel("channel")
                    ),
                    PolicyNode("second", title = "Вторая ветка", target = PolicyTarget.Channel("channel")),
                )
            ),
            channels = listOf(PolicyChannel("channel", "Общий выход", PolicyScope.Device, PolicyTarget.Profile("profile")))
        )
        val state = ExpertUiState(
            policy, profiles = listOf(ExpertProfile("profile", "Тестовый профиль")),
            administrator = true, reducedMotion = true, capabilities = PolicyControlCapabilities(true, true, true),
            exits = listOf(
                ExpertExit(
                    "exit", "Тестовый профиль", "Профиль: Тестовый профиль", ExitPhase.SLEEPING,
                    profileId = "profile", coldStart = true, reason = "Ожидает пользовательский запрос.", canWake = true
                )
            ),
            connections = listOf(
                ExpertConnection(
                    "flow", "Программа не определена", "example.invalid:443", "TCP",
                    "Напрямую", "Совпавших веток не было; выбран путь по умолчанию.", active = false, policyRevision = 1
                )
            ),
            events = listOf(ExpertEvent("event", "12:00", "Схема сохранена", "Это ещё не меняет работающий TUN."))
        )
        val scene = ImageComposeScene(1240, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(state, intents::add, Modifier.fillMaxSize().padding(24.dp))
            }
        }
        try {
            screenshot(scene, "overview")
            assertThat(nodes(scene).any { texts(it).contains("Общий") }).isTrue()
            // v3 sections (m3): «Выходы» became «Конфиги», connections live in «События», protection is «Защита и DNS».
            listOf("Схема", "Проверка пути", "Конфиги", "События", "Защита и DNS").forEach { title ->
                openExpertNavigation(scene)
                val navigation = nodes(scene).first {
                    texts(it).contains(title) && it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                navigation.config[SemanticsActions.OnClick].action!!.invoke()
                screenshot(
                    scene,
                    when (title) {
                        "Схема" -> "schema"
                        "Проверка пути" -> "preview"
                        "Конфиги" -> "exits"
                        "События" -> "events"
                        else -> "protection"
                    }
                )
                if (title == "Схема") {
                    assertThat(nodes(scene).any { "Схема правил" in texts(it) }).isTrue()
                    click(scene, "Граф ▾")
                    click(scene, "Полная схема и каналы")
                    assertThat(
                        nodes(scene).count { node ->
                            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                                it.startsWith("Канал: Общий выход.")
                            }
                        }
                    ).isEqualTo(1)
                }
            }
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `template choice opens its rules and preserves the live policy until explicit apply`() {
        val base = NetworkPolicy()
        val work = base.copy(revision = base.revision + 1, device = PolicyTree(PolicyScope.Device, nodes = listOf(
            PolicyNode("office", title = "Внутренние сайты",
                conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("*.example.invalid")))),
                target = PolicyTarget.Profile("office-exit")),
        )))
        val intents = mutableListOf<ExpertIntent>()
        val chosen = mutableListOf<app.lernet.ui.controls.TemplateAction>()
        var state by mutableStateOf(ExpertUiState(base, phase = ExpertPhase.RUNNING, desiredEnabled = true,
            applied = base, appliedRevision = base.revision, appliedTemplateId = "general",
            tunnelHealth = app.lernet.engine.policy.ExpertTunnelHealth.HEALTHY,
            profiles = listOf(ExpertProfile("office-exit", "Офис")),
            capabilities = PolicyControlCapabilities(true, true, true), reducedMotion = true,
            templateChoices = listOf(app.lernet.config.policy.PolicyTemplateChoice("general", "Общий"),
                app.lernet.config.policy.PolicyTemplateChoice("work", "Рабочий"))))
        val scene = ImageComposeScene(1040, 760, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    DesktopExpert(state, intents::add, topChrome = { app.lernet.desktop.DesktopModeTabs(true, {}, reducedMotion = true) },
                        onTemplateChange = {
                        chosen += it
                        state = state.copy(selectedTemplateId = "work", saved = work, draft = work)
                        null
                    })
                }
            }
        }
        try {
            repeat(3) { scene.render(0).close() }
            click(scene, "Общий")
            click(scene, "Рабочий")
            assertThat(chosen).containsExactly(app.lernet.ui.controls.TemplateAction.Select("work"))
            assertThat(nodes(scene).any { "В сети — Общий" in texts(it) }).isTrue()
            assertThat(nodes(scene).any { "Применить сейчас" in texts(it) }).isTrue()
            screenshot(scene, "template-work-selected")
            click(scene, "ИНАЧЕ")
            assertThat(nodes(scene).any { "Ветка ИНАЧЕ" in texts(it) }).isTrue()
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `start unknown backend emits intent instead of claiming active`() {
        val intents = mutableListOf<ExpertIntent>()
        val state = ExpertUiState(NetworkPolicy(), administrator = true, reducedMotion = true)
        val scene = ImageComposeScene(900, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    state, intents::add,
                    Modifier.fillMaxSize().padding(20.dp)
                )
            }
        }
        try {
            screenshot(scene, "overview-narrow")
            // The v2 pult starts with the lever (a switch), not a «Включить управление» button.
            val start = nodes(scene).first {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> d.startsWith("Постоянный туннель") } &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertThat(start.config.getOrNull(SemanticsProperties.Disabled)).isNull()
            start.config[SemanticsActions.OnClick].action!!.invoke()
            scene.render(0).close()
            assertThat(intents).containsExactly(ExpertIntent.Start)
            assertThat(nodes(scene).none { texts(it).contains("TUN работает") }).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `editor renders portable conditions and explicit child scheme controls without window`() {
        val node = PolicyNode(
            "rule", title = "Сайт через папку", target = PolicyTarget.Profile("profile"),
            conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("*.example.invalid"))))
        )
        val tree = PolicyTree(PolicyScope.Device, listOf(node))
        val profiles = listOf(ExpertProfile("profile", "Профиль", "folder"))
        val state = ExpertUiState(
            NetworkPolicy(device = tree), profiles = profiles,
            folders = listOf(ExpertFolder("folder", "Папка"))
        )
        val scene = ImageComposeScene(900, 850, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertRuleEditor(node, tree, state, {}, {}, onSave = {})
                }
            }
        }
        try {
            screenshot(scene, "rule-editor")
            assertThat(nodes(scene).any { texts(it).contains("Родитель") }).isTrue()
            assertThat(nodes(scene).any { texts(it).contains("Дочерняя схема") }).isTrue()
            assertThat(nodes(scene).any { texts(it).any { value -> value.contains("Ctrl+Enter") } }).isTrue()
            val explainsDomainLimits = nodes(scene).any { node ->
                texts(node).any { value -> value.contains("DoH") && value.contains("ECH") }
            }
            assertThat(explainsDomainLimits).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `startup remains cancellable while backend confirmation is pending`() {
        val intents = mutableListOf<ExpertIntent>()
        val scene = ImageComposeScene(900, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    ExpertUiState(
                        NetworkPolicy(), phase = ExpertPhase.STARTING, busy = true,
                        administrator = true, reducedMotion = true
                    ),
                    intents::add, Modifier.fillMaxSize().padding(20.dp)
                )
            }
        }
        try {
            screenshot(scene, "starting")
            val cancel = nodes(scene).first {
                texts(it).contains("Отменить запуск") &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertThat(cancel.config.getOrNull(SemanticsProperties.Disabled)).isNull()
            cancel.config[SemanticsActions.OnClick].action!!.invoke()
            assertThat(intents).containsExactly(ExpertIntent.Stop)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `saved and applied revisions stay distinct and active scheme can be restored`() {
        val applied = NetworkPolicy(revision = 1)
        val saved = applied.copy(revision = 2)
        var appliedId by mutableStateOf<String?>("general")
        val scene = ImageComposeScene(900, 900, Density(1f, 1.2f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    DesktopExpert(
                        ExpertUiState(
                            saved, applied = applied, appliedRevision = 1, appliedTemplateId = appliedId,
                            phase = ExpertPhase.RUNNING, administrator = true, reducedMotion = true,
                            capabilities = PolicyControlCapabilities(true, true, true),
                        ), {}, Modifier.fillMaxSize().padding(20.dp),
                    )
                }
            }
        }
        try {
            screenshot(scene, "saved-pending-apply-large-text")
            // Saved revision 2 is not the running revision 1: the pult says so in plain words and offers both ways.
            assertThat(nodes(scene).any { texts(it).contains("Есть изменения · применятся после «Применить»") }).isTrue()
            assertThat(nodes(scene).any { texts(it).contains("Применить сейчас") }).isTrue()
            // Replacing the archive invalidates the old template ID, even if the new archive reuses "general".
            appliedId = null
            repeat(3) { scene.render(0).close() }
            assertThat(nodes(scene).any { "В сети — предыдущая схема" in texts(it) }).isTrue()
            val tools = nodes(scene).single { node ->
                "Инструменты" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null
            }
            tools.config[SemanticsActions.OnClick].action!!.invoke()
            repeat(3) { scene.render(0).close() }
            click(scene, "Скопировать действующую схему в черновик…")
            assertThat(nodes(scene).any { "Заменить черновик шаблона?" in texts(it) }).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `external profile edit keeps stable id and failure retains form until matching retry ack`() {
        val profile = ExpertExternalProfile(
            "stable-profile",
            ExternalExitRequest(
                ExternalExitKind.SOCKS5,
                "Локальный выход", "127.0.0.1", 1080
            ),
            fingerprint = "expected-fingerprint"
        )
        var state by mutableStateOf(ExpertUiState(NetworkPolicy(), externalProfiles = listOf(profile)))
        val intents = mutableListOf<ExpertIntent>()
        var closed = false
        val scene = ImageComposeScene(900, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertExternalExitEditor(profile, state, intents::add, { closed = true })
                }
            }
        }
        try {
            screenshot(scene, "external-editor")
            click(scene, "Сохранить конфиг · Ctrl+Enter")
            val first = intents.single() as ExpertIntent.SaveExternalExit
            assertThat(first.profileId).isEqualTo("stable-profile")
            assertThat(first.expectedFingerprint).isEqualTo("expected-fingerprint")
            assertThat(closed).isFalse()
            state = state.copy(
                externalSaveError = "Диск недоступен; поля сохранены в окне.",
                externalSaveErrorRequestId = first.requestId,
            )
            screenshot(scene, "external-editor-write-failed")
            assertThat(closed).isFalse()
            assertThat(nodes(scene).any { texts(it).contains("Запись не завершена") }).isTrue()
            click(scene, "Сохранить конфиг · Ctrl+Enter")
            val retry = intents.last() as ExpertIntent.SaveExternalExit
            assertThat(retry.requestId).isNotEqualTo(first.requestId)
            assertThat(retry.request.host).isEqualTo("127.0.0.1")
            assertThat(closed).isFalse()
            state = state.copy(
                externalSaveError = null,
                externalSaveErrorRequestId = null,
                externalSaveAck = retry.requestId,
            )
            repeat(3) { scene.render(0).close() }
            assertThat(closed).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `corporate form displays verified interface evidence and never invents a raw direct exit`() {
        val binding = VerifiedInterfaceBinding("12345678-1234-1234-1234-123456789abc", "Системный VPN", 17)
        val profile = ExpertExternalProfile(
            "corporate",
            ExternalExitRequest(
                ExternalExitKind.CORPORATE_INTERFACE,
                "Рабочая сеть", binding = binding, dnsServer = "192.0.2.53"
            ),
            fingerprint = "corporate-fingerprint"
        )
        val interfaces = listOf(ExpertInterface(binding, "Системный VPN", "Подключён", true))
        val state = ExpertUiState(NetworkPolicy(), interfaces = interfaces)
        val intents = mutableListOf<ExpertIntent>()
        val scene = ImageComposeScene(900, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertExternalExitEditor(profile, state, intents::add, {})
                }
            }
        }
        try {
            screenshot(scene, "corporate-editor")
            assertThat(nodes(scene).any { texts(it).contains("Подтверждённый адаптер") }).isTrue()
            click(scene, "Сохранить конфиг · Ctrl+Enter")
            val save = intents.single() as ExpertIntent.SaveExternalExit
            assertThat(save.request.kind).isEqualTo(ExternalExitKind.CORPORATE_INTERFACE)
            assertThat(save.request.binding).isEqualTo(binding)
            assertThat(save.request.dnsServer).isEqualTo("192.0.2.53")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `advanced corporate import remains visible and read only without replacing credentials or selected fields`() {
        val binding = VerifiedInterfaceBinding("12345678-1234-1234-1234-123456789abc", "Рабочий адаптер", 23)
        val profile = ExpertProfile(
            "advanced-corporate", "Импортированный корпоративный профиль", protocol = "direct",
            interfaceBinding = binding
        )
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Profile(profile.id)))
        val state = ExpertUiState(policy, profiles = listOf(profile))
        val intents = mutableListOf<ExpertIntent>()
        val scene = ImageComposeScene(1000, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertExits(state, intents::add, Modifier.fillMaxSize().padding(20.dp))
                }
            }
        }
        try {
            screenshot(scene, "advanced-interface-list")
            assertThat(nodes(scene).any { texts(it).contains(profile.name) }).isTrue()
            click(scene, "Сохранённая привязка и пути")
            screenshot(scene, "advanced-interface-details")
            assertThat(nodes(scene).any { texts(it).contains("Номер: 23") }).isTrue()
            assertThat(nodes(scene).any { texts(it).contains("GUID: ${binding.guid}") }).isTrue()
            assertThat(nodes(scene).none { texts(it).contains("Сохранить конфиг · Ctrl+Enter") }).isTrue()
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `constructed corporate transport never claims verified resource reachability`() {
        val binding = VerifiedInterfaceBinding("12345678-1234-1234-1234-123456789abc", "Рабочий VPN", 23)
        val profile = ExpertProfile("corporate", "Рабочий выход", interfaceBinding = binding)
        val exit = ExpertExit(
            "exit", profile.name, "Привязка к рабочему адаптеру", ExitPhase.READY,
            profileId = profile.id, reason = "Транспорт создан; проверка ресурса не проводилась."
        )
        val state = ExpertUiState(NetworkPolicy(), profiles = listOf(profile), exits = listOf(exit))
        val intents = mutableListOf<ExpertIntent>()
        val scene = ImageComposeScene(1100, 1000, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertExits(state, intents::add, Modifier.fillMaxSize().padding(20.dp))
                }
            }
        }
        try {
            screenshot(scene, "corporate-transport-only")
            assertThat(nodes(scene).any { texts(it).contains("Интерфейсный выход подготовлен") }).isTrue()
            assertThat(nodes(scene).none { texts(it).contains("Работает") }).isTrue()
            assertThat(exitDisplayColor(exit, state)).isEqualTo(ExpertColors.blue)
            click(scene, "Подробнее")
            assertThat(nodes(scene).any { texts(it).contains("Готовность не равна доступности") }).isTrue()
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).first { texts(it).contains(text) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        node.config[SemanticsActions.OnClick].action!!.invoke()
        repeat(3) { scene.render(0).close() }
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(0).close() }
        scene.render(0).use { image ->
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

/** The wide map keeps the same destinations under the brand menu. Other pages use the sidebar. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun openExpertNavigation(scene: ImageComposeScene): Boolean {
    fun find(node: SemanticsNode): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Разделы ▾" } &&
            node.config.getOrNull(SemanticsActions.OnClick) != null) return node
        return node.children.firstNotNullOfOrNull(::find)
    }
    val menu = scene.semanticsOwners.firstNotNullOfOrNull { find(it.rootSemanticsNode) } ?: return false
    menu.config[SemanticsActions.OnClick].action!!.invoke()
    repeat(3) { scene.render(0).close() }
    return true
}
