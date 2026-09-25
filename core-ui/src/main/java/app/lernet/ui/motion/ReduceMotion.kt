package app.lernet.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val resolver = context.contentResolver
        val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        animator == 0f || transition == 0f
    }
}

fun motionMs(reduceMotion: Boolean, millis: Int): Int = if (reduceMotion) 0 else millis

fun <T> motionTween(reduceMotion: Boolean, millis: Int) = tween<T>(motionMs(reduceMotion, millis))
