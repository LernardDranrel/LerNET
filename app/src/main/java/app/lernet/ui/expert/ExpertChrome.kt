package app.lernet.ui.expert

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.controls.ExpertFrameSpec
import app.lernet.ui.controls.ExpertFramedBox
import app.lernet.ui.icons.LerNetSymbols
import kotlinx.coroutines.delay

/** Expert's own bottom menu: Пульт · Схема · Конфиги · Проверка · События. «Защита и DNS» is in ⋮ (audit N4, m4). */
internal val ExpertBottomSections = listOf(
    ExpertSection.OVERVIEW,
    ExpertSection.SCHEMA,
    ExpertSection.EXITS,
    ExpertSection.SIMULATION,
    ExpertSection.JOURNAL,
)

@Composable
private fun ExpertSection.icon(): Painter = when (this) {
    ExpertSection.OVERVIEW -> LerNetSymbols.power()
    ExpertSection.SCHEMA -> LerNetSymbols.route()
    ExpertSection.EXITS -> LerNetSymbols.apps()
    ExpertSection.SIMULATION -> LerNetSymbols.swapVert()
    else -> LerNetSymbols.info()
}

@Composable
internal fun ExpertBottomMenu(section: ExpertSection, onSection: (ExpertSection) -> Unit) {
    val description = stringResource(R.string.expert_bottom_menu)
    NavigationBar(
        Modifier.testTag("expert_bottom_menu").semantics { contentDescription = description },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        ExpertBottomSections.forEach { item ->
            NavigationBarItem(
                selected = section == item,
                onClick = { onSection(item) },
                icon = { Icon(item.icon(), contentDescription = null) },
                label = { Text(stringResource(item.short), maxLines = 1) },
                modifier = Modifier.testTag("expert_tab_${item.name.lowercase()}"),
            )
        }
    }
}

/** First Expert entry shows a splash once; afterwards the frame's chamfers and lamps fade in instead. */
internal class ExpertSplashState(val firstEntry: Boolean) {
    var visible by mutableStateOf(firstEntry)
}

internal object ExpertSplashMemory {
    private const val PREFS = "lernet_ui"
    private const val KEY = "expert_splash_seen"
    fun seen(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

internal const val EXPERT_SPLASH_MS = 1_500L

@Composable
internal fun rememberExpertSplash(context: Context): ExpertSplashState {
    val state = remember { ExpertSplashState(firstEntry = !ExpertSplashMemory.seen(context)) }
    LaunchedEffect(state) {
        if (state.visible) {
            ExpertSplashMemory.markSeen(context)
            delay(EXPERT_SPLASH_MS)
            state.visible = false
        }
    }
    return state
}

@Composable
internal fun ExpertSplash() {
    ExpertFramedBox(
        ExpertFrameSpec.Phone,
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("expert_splash"),
        lit = 1f,
        animateIn = true,
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LerNetLogo(size = 72.dp)
            Text(stringResource(R.string.expert_splash_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(R.string.expert_splash_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
