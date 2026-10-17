package app.lernet.ui.home

import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.vpn.expert.AndroidVpnLockdown
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LockdownHintTest {
    private val vpn = ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, mode = RunMode.FULL_VPN)

    @Test
    fun shownOnceConnectedWithoutSystemBlock() {
        assertThat(LockdownHint.visible(vpn, dismissed = false, lockdown = AndroidVpnLockdown.DISABLED)).isTrue()
        assertThat(LockdownHint.visible(vpn, dismissed = false, lockdown = AndroidVpnLockdown.UNKNOWN)).isTrue()
    }

    @Test
    fun hiddenWhenAndroidConfirmsBlockOrUserDismissed() {
        assertThat(LockdownHint.visible(vpn, dismissed = false, lockdown = AndroidVpnLockdown.ENABLED)).isFalse()
        assertThat(LockdownHint.visible(vpn, dismissed = true, lockdown = AndroidVpnLockdown.DISABLED)).isFalse()
    }

    @Test
    fun hiddenOutsideConnectedVpn() {
        assertThat(LockdownHint.visible(vpn.copy(mode = RunMode.PROXY), false, AndroidVpnLockdown.DISABLED)).isFalse()
        assertThat(LockdownHint.visible(vpn.copy(state = ConnectionState.CONNECTING), false, AndroidVpnLockdown.DISABLED)).isFalse()
        assertThat(LockdownHint.visible(vpn, false, null)).isFalse()
    }
}
