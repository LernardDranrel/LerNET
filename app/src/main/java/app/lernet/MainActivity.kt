package app.lernet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lernet.engine.log.CrashTrail
import app.lernet.ui.nav.LerNetAppRoot
import app.lernet.ui.theme.LerNetTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashTrail.mark("MainActivity.onCreate")
        enableEdgeToEdge()
        CrashTrail.mark("MainActivity.setContent")
        setContent {
            LerNetTheme {
                LerNetAppRoot()
            }
        }
        CrashTrail.mark("MainActivity.onCreate done")
    }
}
