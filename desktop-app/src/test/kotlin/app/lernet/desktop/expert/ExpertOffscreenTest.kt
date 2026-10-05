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
            assertThat(nodes(scene).any { texts(it).contains("Управление сетью") }).isTrue()
            listOf("Схема", "Проверка пути", "Выходы", "Соединения", "События", "Защита").forEach { title ->
                val navigation = nodes(scene).first {
                    texts(it).contains(title) && it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                navigation.config[SemanticsActions.OnClick].action!!.invoke()
                screenshot(
                    scene,
                    when (title) {
                        "Схема" -> "schema"
                        "Проверка пути" -> "preview"
                        "Выходы" -> "exits"
                        "Соединения" -> "traffic"
                        "События" -> "events"
                        else -> "protection"
                    }
                )
                if (title == "Схема") {
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
            val start = nodes(scene).first {
                texts(it).contains("Включить управление") &&
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
        val scene = ImageComposeScene(900, 900, Density(1f, 1.2f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    ExpertUiState(
                        saved, applied = applied, appliedRevision = 1, phase = ExpertPhase.RUNNING,
                        administrator = true, reducedMotion = true,
                        capabilities = PolicyControlCapabilities(true, true, true)
                    ),
                    {},
                    Modifier.fillMaxSize().padding(20.dp)
                )
            }
        }
        try {
            screenshot(scene, "saved-pending-apply-large-text")
            assertThat(nodes(scene).any { texts(it).contains("Сохранена: 2 · применена: 1") }).isTrue()
            assertThat(nodes(scene).any { texts(it).contains("Вернуть применённую версию в черновик") }).isTrue()
            assertThat(nodes(scene).any { texts(it).contains("Применить к работающему TUN") }).isTrue()
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
            click(scene, "Сохранить профиль · Ctrl+Enter")
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
            click(scene, "Сохранить профиль · Ctrl+Enter")
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
            click(scene, "Сохранить профиль · Ctrl+Enter")
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
            assertThat(nodes(scene).none { texts(it).contains("Сохранить профиль · Ctrl+Enter") }).isTrue()
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
