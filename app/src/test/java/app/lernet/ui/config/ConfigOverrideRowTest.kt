package app.lernet.ui.config

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lernet.engine.compile.FieldSource
import app.lernet.engine.compile.FieldView
import app.lernet.engine.compile.OverrideReason
import app.lernet.engine.compile.TruthFieldId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConfigOverrideRowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun underlayDnsShowsStrikeAndYellowChip() {
        val field = FieldView(
            id = TruthFieldId.DNS,
            profileValue = "tls dns-remote 1.1.1.1 detour=proxy",
            globalValue = null,
            effectiveValue = "final=dns-direct; udp dns-direct 1.1.1.1",
            source = FieldSource.SYSTEM,
            overridden = true,
            reason = OverrideReason.DNS_DROPPED_PROXIED,
            reasonDetail = "dns dropped proxied resolver tag=dns-remote",
        )
        rule.setContent {
            ConfigOverrideRow(field = field, label = "Серверы DNS")
        }
        rule.onNodeWithText("переопределено системой").assertIsDisplayed()
        rule.onNodeWithText("tls dns-remote 1.1.1.1 detour=proxy").assertIsDisplayed()
        rule.onNodeWithTag("cfg-profile-struck").assertIsDisplayed()
        rule.onNodeWithText("Как уйдёт: final=dns-direct; udp dns-direct 1.1.1.1", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun profileDnsHasNoSystemChip() {
        val field = FieldView(
            id = TruthFieldId.DNS,
            profileValue = "tls dns-remote 1.1.1.1 detour=proxy",
            globalValue = null,
            effectiveValue = "final=dns-remote; tls dns-remote 1.1.1.1 detour=proxy",
            source = FieldSource.PROFILE,
            overridden = false,
            reason = null,
            reasonDetail = null,
        )
        rule.setContent {
            ConfigOverrideRow(field = field, label = "Серверы DNS")
        }
        rule.onNodeWithText("переопределено системой").assertDoesNotExist()
        rule.onNodeWithTag("cfg-profile-struck").assertDoesNotExist()
        rule.onNodeWithText("final=dns-remote; tls dns-remote 1.1.1.1 detour=proxy").assertIsDisplayed()
    }
}
