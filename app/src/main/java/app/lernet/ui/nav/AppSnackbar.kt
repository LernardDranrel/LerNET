package app.lernet.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/** Audit G6: the app has one snackbar queue; screens that draw their own host (Expert) reuse it. */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }
