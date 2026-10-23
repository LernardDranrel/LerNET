package app.lernet.ui.controls

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertFrameSpecTest {
    @Test
    fun phoneUsesFullWidthAndEdgeLamps() {
        val phone = ExpertFrameSpec.Phone
        assertThat(phone.inset).isEqualTo(0.dp)
        assertThat(phone.resolvedContentInsetHorizontal).isEqualTo(0.dp)
        assertThat(phone.resolvedContentInsetVertical).isEqualTo(10.dp)
        assertThat(phone.style).isEqualTo(ExpertFrameStyle.LINE_ROUND)
    }

    @Test
    fun windowsKeepsShellOwnedInset() {
        val windows = ExpertFrameSpec.Windows
        assertThat(windows.contentInset).isEqualTo(0.dp)
        assertThat(windows.resolvedContentInsetHorizontal).isEqualTo(0.dp)
        assertThat(windows.resolvedContentInsetVertical).isEqualTo(0.dp)
        assertThat(windows.style).isEqualTo(ExpertFrameStyle.DOUBLE_SLOT)
    }
}
