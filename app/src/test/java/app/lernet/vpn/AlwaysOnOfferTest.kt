package app.lernet.vpn

import android.app.Application
import android.app.NotificationManager
import app.lernet.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlwaysOnOfferTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @After fun reset() { AlwaysOnOffer.consume() }

    @Test
    fun offerOpensLerNetWithConnectAction() {
        shadowOf(manager).setNotificationsEnabled(true)
        AlwaysOnOffer.show(context)
        val posted = shadowOf(manager).allNotifications.single()
        assertThat(shadowOf(posted).contentTitle.toString()).isEqualTo(context.getString(R.string.always_on_offer_title))
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertThat(intent.action).isEqualTo(AlwaysOnOffer.ACTION_CONNECT_LAST)
        assertThat(intent.component?.className).isEqualTo("app.lernet.MainActivity")
    }

    @Test
    fun tapIsTakenExactlyOnceAndRemovesTheOffer() {
        shadowOf(manager).setNotificationsEnabled(true)
        AlwaysOnOffer.show(context)
        AlwaysOnOffer.onTapped(context)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
        assertThat(AlwaysOnOffer.connectRequested.value).isTrue()
        assertThat(AlwaysOnOffer.consume()).isTrue()
        assertThat(AlwaysOnOffer.consume()).isFalse()
    }

    @Test
    fun nothingPostedWhenNotificationsAreOff() {
        shadowOf(manager).setNotificationsEnabled(false)
        AlwaysOnOffer.show(context)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }
}
