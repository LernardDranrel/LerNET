package app.lernet.ui.importcfg

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ImportUnusedPartsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun unusedPartsAreListedOnScreen() {
        rule.setContent {
            ImportUnusedParts(listOf("входы (inbounds: 2) — LerNET создаёт свои", "раздел «log»"))
        }
        rule.onNodeWithText("Эти части конфига не используются:").assertIsDisplayed()
        rule.onNodeWithText("• входы (inbounds: 2) — LerNET создаёт свои").assertIsDisplayed()
        rule.onNodeWithText("• раздел «log»").assertIsDisplayed()
    }

    @Test
    fun nothingShownWhenEverythingIsUsed() {
        rule.setContent { ImportUnusedParts(emptyList()) }
        rule.onNodeWithTag("import_unused_parts").assertDoesNotExist()
    }
}
