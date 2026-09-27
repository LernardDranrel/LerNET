package app.lernet.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.config.model.Group
import app.lernet.config.repo.RouteOwners
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionHeroTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun connectingPowerControlLetsUserCancelTheSession() {
        var toggles = 0
        rule.setContent {
            LerNetTheme {
                Box(Modifier.width(320.dp)) {
                    ConnectionHero(
                        connection = ConnectionState.CONNECTING,
                        status = "Подключение…",
                        enabled = true,
                        onToggle = { toggles++ },
                        onOpenRoutes = null,
                    ) { Text("Выбранный сервер") }
                }
            }
        }
        rule.onNodeWithContentDescription("Отключить").assertIsEnabled().performClick()
        rule.runOnIdle { assertThat(toggles).isEqualTo(1) }
    }

    @Test
    fun emptyProfileCannotStartConnection() {
        var toggles = 0
        rule.setContent {
            LerNetTheme {
                ConnectionHero(
                    connection = ConnectionState.DISCONNECTED,
                    status = "Отключено",
                    enabled = false,
                    onToggle = { toggles++ },
                    onOpenRoutes = null,
                ) { Text("Выберите профиль") }
            }
        }
        rule.onNodeWithContentDescription("Подключить").assertIsNotEnabled()
        rule.runOnIdle { assertThat(toggles).isEqualTo(0) }
    }

    @Test
    fun mobileHomeShowsServerAndTunnelLatency() {
        var openedOwner: String? = null
        val profile = Profile(
            id = "selected",
            name = "Amsterdam · основной",
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            source = ProfileSource.VLESS,
            selectedOutboundId = "main",
            outbounds = emptyList(),
            subscriptionUrl = null,
            lastRefreshEpochMs = null,
        )
        rule.setContent {
            LerNetTheme {
                HomeScreen(
                    state = HomeUiState(
                        snapshot = ConnectionSnapshot.idle().copy(
                            state = ConnectionState.CONNECTED,
                            activeProfileId = profile.id,
                            serverTcpMs = 46,
                        ),
                        activeProfile = profile,
                        profiles = listOf(profile),
                        engineAvailable = true,
                        probes = mapOf(profile.id to ProfileProbe(tcpMs = 46, reachable = true, tunnelMs = 147, tunnelChecked = true)),
                    ),
                    onIntent = {},
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onOpenDiag = {},
                    onOpenRoutes = { openedOwner = it },
                    onRefreshHop = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Отключить").assertIsDisplayed()
        rule.onNodeWithContentDescription("Открыть схему маршрутизации").assertIsEnabled().performClick()
        rule.runOnIdle { assertThat(openedOwner).isEqualTo(profile.id) }
        rule.onNodeWithText(profile.name).assertIsDisplayed()
        rule.onNodeWithText("46 мс").assertIsDisplayed()
        rule.onNodeWithText("147 мс").assertIsDisplayed()
        rule.onNodeWithText("HTTPS · через профиль").assertIsDisplayed()
        val screenshot = File("build/reports/ui/mobile-home.png")
        screenshot.parentFile?.mkdirs()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            screenshot.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun routeShortcutOpensRunningProfilesGroupAndStandaloneProfile() {
        val selected = Profile(
            id = "selected",
            name = "Нидерланды",
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            source = ProfileSource.VLESS,
            selectedOutboundId = "main",
            outbounds = emptyList(),
            subscriptionUrl = null,
            lastRefreshEpochMs = null,
        )
        val running = selected.copy(id = "running", name = "Германия")
        val group = Group(id = "folder", name = "Европа", profileIds = listOf(running.id))
        val connected = HomeUiState(
            snapshot = ConnectionSnapshot.idle().copy(
                state = ConnectionState.CONNECTED,
                activeProfileId = running.id,
            ),
            activeProfile = selected,
            profiles = listOf(selected, running),
            groups = listOf(group),
        )
        assertThat(homeRouteOwnerId(connected)).isEqualTo(RouteOwners.group(group.id))
        assertThat(homeRouteOwnerId(connected.copy(groups = emptyList()))).isEqualTo(running.id)
        assertThat(homeRouteOwnerId(connected.copy(snapshot = ConnectionSnapshot.idle()))).isEqualTo(selected.id)
    }
}
