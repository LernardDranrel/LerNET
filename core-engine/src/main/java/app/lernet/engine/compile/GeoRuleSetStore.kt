package app.lernet.engine.compile

import android.content.Context
import app.lernet.routing.GeoRuleSets
import java.io.File

/**
 * Copies bundled `.srs` files to app storage. sing-box local rule-sets need a filesystem path.
 * Each start overwrites the files so an APK update replaces the sets. No network fetch.
 */
object GeoRuleSetStore {
    fun install(context: Context): String {
        val dir = File(context.filesDir, "rule-set")
        if (!dir.isDirectory && !dir.mkdirs()) {
            error("rule-set directory was not created")
        }
        GeoRuleSets.bundled.forEach { code ->
            val name = GeoRuleSets.fileName(code)
            context.assets.open("rule-set/$name").use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dir.absolutePath
    }
}
