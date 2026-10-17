package app.lernet.ui.home

import android.Manifest
import android.app.Application
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationHintTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val connected = ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED)

    @Test
    fun askedOnlyOnceOnAndroid13AndNewer() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(NotificationHint.shouldAsk(context, alreadyAsked = false, sdk = 33)).isTrue()
        assertThat(NotificationHint.shouldAsk(context, alreadyAsked = true, sdk = 33)).isFalse()
        assertThat(NotificationHint.shouldAsk(context, alreadyAsked = false, sdk = 32)).isFalse()
    }

    @Test
    fun notAskedWhenAlreadyGranted() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(NotificationHint.shouldAsk(context, alreadyAsked = false, sdk = 33)).isFalse()
    }

    @Test
    fun softHintOnlyWhenNotificationsAreOffAndNotHidden() {
        assertThat(NotificationHint.visible(connected, dismissed = false, enabled = false)).isTrue()
        assertThat(NotificationHint.visible(connected, dismissed = true, enabled = false)).isFalse()
        assertThat(NotificationHint.visible(connected, dismissed = false, enabled = true)).isFalse()
        assertThat(NotificationHint.visible(connected, dismissed = false, enabled = null)).isFalse()
        assertThat(NotificationHint.visible(connected.copy(state = ConnectionState.DISCONNECTED), false, false)).isFalse()
    }
}
