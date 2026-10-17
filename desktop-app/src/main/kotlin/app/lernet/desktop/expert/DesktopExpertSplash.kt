package app.lernet.desktop.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lernet.desktop.DesktopStore
import app.lernet.ui.controls.ExpertFrameSpec
import app.lernet.ui.controls.ExpertFramedBox
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay

/** First Expert entry on Windows shows a splash once; the marker file lives next to state.json. */
internal class DesktopExpertSplashMemory(private val directory: Path = DesktopStore.defaultDirectory()) {
    private val marker get() = directory.resolve("expert-splash-seen")
    fun seen(): Boolean = runCatching { Files.exists(marker) }.getOrDefault(true)
    fun markSeen() {
        runCatching {
            Files.createDirectories(directory)
            if (!Files.exists(marker)) Files.writeString(marker, "1")
        }
    }
}

internal class DesktopExpertSplashState(val firstEntry: Boolean) {
    var visible by mutableStateOf(firstEntry)
}

internal const val DESKTOP_EXPERT_SPLASH_MS = 1_500L

@Composable
internal fun rememberDesktopExpertSplash(memory: DesktopExpertSplashMemory = remember { DesktopExpertSplashMemory() }): DesktopExpertSplashState {
    val state = remember { DesktopExpertSplashState(firstEntry = !memory.seen()) }
    LaunchedEffect(state) {
        if (state.visible) {
            memory.markSeen()
            delay(DESKTOP_EXPERT_SPLASH_MS)
            state.visible = false
        }
    }
    return state
}

@Composable
internal fun DesktopExpertSplash() {
    ExpertFramedBox(
        ExpertFrameSpec.Windows,
        Modifier.fillMaxSize().background(ExpertColors.background).semantics { contentDescription = "Заставка Эксперта" },
        lit = 1f,
        animateIn = true,
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Эксперт", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Здесь ты сам решаешь, куда идёт каждое соединение этого компьютера. Рычаг — на пульте, правила — на схеме.",
                color = ExpertColors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
