package app.lernet.ui.routes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lernet.ui.icons.LerNetSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only exact Android package identity can provide an application icon. */
@Composable
internal fun InstalledApplicationIcon(packageName: String?) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) {
            packageName?.let {
                runCatching { context.packageManager.getApplicationIcon(it).toBitmap(48, 48).asImageBitmap() }.getOrNull()
            }
        }
    }
    val bitmap = icon
    if (bitmap == null) {
        Icon(LerNetSymbols.info(), contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
    }
}
