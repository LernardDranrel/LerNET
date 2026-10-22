package app.lernet.vpn

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BootRestoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun decisionNeedsTheSettingAndVpnPermission() {
        assertThat(BootRestoreDecision.decide(enabled = false, permitted = true, expertWanted = true)).isEqualTo(BootRestoreDecision.OFF)
        assertThat(BootRestoreDecision.decide(enabled = true, permitted = false, expertWanted = false)).isEqualTo(BootRestoreDecision.NO_PERMISSION)
        assertThat(BootRestoreDecision.decide(enabled = true, permitted = true, expertWanted = true)).isEqualTo(BootRestoreDecision.EXPERT)
        assertThat(BootRestoreDecision.decide(enabled = true, permitted = true, expertWanted = false)).isEqualTo(BootRestoreDecision.SIMPLE)
    }

    @Test
    fun settingIsOnByDefaultAndRemembered() {
        assertThat(BootRestorePreference.enabled(context)).isTrue()
        BootRestorePreference.set(context, false)
        assertThat(BootRestorePreference.enabled(context)).isFalse()
        BootRestorePreference.set(context, true)
    }

    @Test
    fun bootRestoresQuietlyWithoutTheAlwaysOnOffer() {
        val calls = mutableListOf<Boolean>()
        SimpleServiceRestorer.register(restore = { calls += it }, clear = {}, clearForExpert = {})
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(context, Intent("app.lernet.SOMETHING_ELSE"))
        assertThat(calls).containsExactly(false)
        BootRestorePreference.set(context, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertThat(calls).containsExactly(false)
        BootRestorePreference.set(context, true)
        SimpleServiceRestorer.restore()
        assertThat(calls).containsExactly(false, true).inOrder()
        // Without VPN permission nothing starts.
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETUP_VPN"))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertThat(calls).containsExactly(false, true).inOrder()
        org.robolectric.shadows.ShadowVpnService.reset()
    }

    @Test
    fun receiverIsDeclaredForBootCompleted() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), 0,
        )
        assertThat(receivers.map { it.activityInfo.name }).contains(BootReceiver::class.java.name)
    }
}
