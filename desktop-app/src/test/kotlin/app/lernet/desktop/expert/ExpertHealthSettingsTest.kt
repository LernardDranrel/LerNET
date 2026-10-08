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
import app.lernet.desktop.desktopColors
import app.lernet.desktop.desktopTypography
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyHealthSettings
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class ExpertHealthSettingsTest {
    @Test
    fun `second inputs retain exact millisecond precision and support comma keyboard`() {
        assertThat(healthFormSettings("1,001", "7.125", "4,5", "2")).isEqualTo(
            PolicyHealthSettings(1_001, 7_125, 4_500, 2),
        )
        assertThat(healthSeconds(7_125)).isEqualTo("7.125")
        assertThat(healthSeconds(3_000)).isEqualTo("3")
        assertThat(healthFormSettings("1", "60", "15", "10")).isNotNull()
    }

    @Test
    fun `invalid budgets and reversed intervals never become a draft policy`() {
        listOf("0", "0.999", "61", "NaN", "1e1", "1.0001", "9999999999999999999999999").forEach {
            assertThat(healthFormSettings(it, "60", "4", "2")).isNull()
        }
        assertThat(healthFormSettings("7", "3", "4", "2")).isNull()
        assertThat(healthFormSettings("3", "7", "15.001", "2")).isNull()
        assertThat(healthFormSettings("3", "7", "4", "1.5")).isNull()
        assertThat(healthFormSettings("3", "7", "4", "0")).isNull()
        assertThat(healthFormSettings("3", "7", "4", "11")).isNull()
    }

    @Test
    fun `health editor waits for exact draft acknowledgement and retains fields after write failure`() {
        val policy = NetworkPolicy()
        var state by mutableStateOf(ExpertUiState(policy, administrator = true))
        val intents = mutableListOf<ExpertIntent>()
        var dismissed = false
        val scene = ImageComposeScene(940, 940, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    ExpertHealthSettings(state, intents::add) { dismissed = true }
                }
            }
        }
        try {
            render(scene)
            val field = nodes(scene).first {
                texts(it).contains("Минимальный интервал, секунды") &&
                    it.config.getOrNull(SemanticsActions.SetText) != null
            }
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("2,5"))
            render(scene)
            click(scene, "Сохранить в черновик · Ctrl+Enter")
            val edited = (intents.single() as ExpertIntent.EditPolicy).policy
            assertThat(edited.health.minimumIntervalMs).isEqualTo(2_500)
            assertThat(edited.revision).isEqualTo(policy.revision)
            assertThat(state.saved).isEqualTo(policy)
            assertThat(dismissed).isFalse()
            state = state.copy(
                draft = edited, error = "Запись не подтверждена",
                events = listOf(ExpertEvent("failed-1", "12:00", "Ошибка записи", "Запись не подтверждена", warning = true)),
            )
            render(scene)
            assertThat(dismissed).isFalse()
            assertThat(nodes(scene).any { texts(it).contains("Изменение не подтверждено") }).isTrue()
            screenshot(scene, "health-editor-write-failed")
            click(scene, "Сохранить в черновик · Ctrl+Enter")
            assertThat(intents).hasSize(2)
            assertThat((intents.last() as ExpertIntent.EditPolicy).policy).isEqualTo(edited)
            assertThat(dismissed).isFalse()
            state = state.copy(
                events = state.events + ExpertEvent("failed-2", "12:01", "Ошибка записи", "Запись не подтверждена", true),
            )
            render(scene)
            assertThat(dismissed).isFalse()
            click(scene, "Сохранить в черновик · Ctrl+Enter")
            assertThat(intents).hasSize(3)
            assertThat((intents.last() as ExpertIntent.EditPolicy).policy).isEqualTo(edited)
            state = state.copy(error = null)
            render(scene)
            assertThat(dismissed).isTrue()
            assertThat(intents.all { it is ExpertIntent.EditPolicy }).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `draft acknowledgement rejects stale errors and foreign draft and detects identical new failures`() {
        val original = NetworkPolicy()
        val edited = original.copy(health = PolicyHealthSettings(minimumIntervalMs = 2_500))
        val failed = ExpertUiState(
            saved = original, draft = edited, error = "Не удалось записать",
            events = listOf(ExpertEvent("error-1", "12:00", "Запись", "Ошибка", warning = true)),
        )
        val retry = ExpertDraftSubmission.capture(edited, failed)
        assertThat(retry.response(failed)).isEqualTo(ExpertDraftResponse.WAITING)
        assertThat(retry.response(failed.copy(draft = original, error = null))).isEqualTo(ExpertDraftResponse.WAITING)
        val failedAgain = failed.copy(events = failed.events + ExpertEvent("error-2", "12:01", "Запись", "Ошибка", true))
        assertThat(retry.response(failedAgain)).isEqualTo(ExpertDraftResponse.FAILED)
        assertThat(retry.response(failed.copy(error = "Другая ошибка"))).isEqualTo(ExpertDraftResponse.FAILED)
        assertThat(retry.response(failed.copy(error = null))).isEqualTo(ExpertDraftResponse.CONFIRMED)
    }

    @Test
    fun `overview health action opens draft editor without starting the network`() {
        val intents = mutableListOf<ExpertIntent>()
        val scene = ImageComposeScene(1100, 1100, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    DesktopExpert(
                        ExpertUiState(NetworkPolicy(), administrator = true, reducedMotion = true),
                        intents::add, Modifier.fillMaxSize().padding(24.dp),
                    )
                }
            }
        }
        try {
            render(scene)
            nodes(scene).first {
                "Настройки экспертного режима" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }.config[SemanticsActions.OnClick].action!!.invoke()
            render(scene)
            click(scene, "Проверки связи")
            assertThat(nodes(scene).any { texts(it).contains("Сохранить в черновик · Ctrl+Enter") }).isTrue()
            assertThat(intents).isEmpty()
            screenshot(scene, "health-editor")
        } finally {
            scene.close()
        }
    }

    private fun render(scene: ImageComposeScene) {
        repeat(3) { scene.render(0).close() }
    }

    private fun click(scene: ImageComposeScene, text: String) {
        val action = nodes(scene).first {
            texts(it).contains(text) && it.config.getOrNull(SemanticsActions.OnClick) != null
        }
        action.config[SemanticsActions.OnClick].action!!.invoke()
        render(scene)
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        render(scene)
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
