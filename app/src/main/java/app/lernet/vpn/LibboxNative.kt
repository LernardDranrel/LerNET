package app.lernet.vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.nativebridge.FixAndroidStackPolicy
import app.lernet.engine.nativebridge.NativeBootstrapSession
import app.lernet.engine.nativebridge.NativeBridge
import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

object LibboxNative {
    const val PINNED_VERSION = "1.14.1-lx.8"

    private val session = NativeBootstrapSession()

    @Volatile
    var loadedVersion: String? = null
        private set

    val isReady: Boolean
        get() = session.isReady

    fun warmupFromApplication(context: Context) {
        val app = context.applicationContext
        val paths = setupPaths(app)
        val bridge = GomobileBridge(app, paths.base, paths.working, paths.temp)
        CrashTrail.mark("application: SFA warmup (setLocale+setup, no Seq.setContext, no loadLibrary)")
        try {
            session.prepareLocale(bridge, ::onLocaleFailure)
        } catch (error: Throwable) {
            CrashTrail.recordFailure("application.prepareLocale", error)
        }
        Thread(
            {
                runCatching { bootstrap(app, paths.base, paths.working, paths.temp) }
                    .onFailure { error -> CrashTrail.recordFailure("application.setup", error) }
            },
            "lernet-libbox-setup",
        ).start()
    }

    fun bootstrap(
        context: Context,
        basePath: String,
        workingPath: String,
        tempPath: String,
    ): String {
        loadedVersion?.let { return it }
        val version = session.await(
            GomobileBridge(context.applicationContext, basePath, workingPath, tempPath),
            onLocaleFailure = ::onLocaleFailure,
        )
        loadedVersion = version
        return version
    }

    private fun onLocaleFailure(error: Throwable) {
        CrashTrail.mark("native: setLocale failed ${error.message}")
        LerNetLog.w(TAG, "setLocale failed: ${error.message}", error)
    }

    private fun setupPaths(context: Context): SetupPaths {
        val root = File(context.filesDir, "libbox")
        val working = File(root, "working")
        val temp = File(root, "temp")
        working.mkdirs()
        temp.mkdirs()
        return SetupPaths(root.absolutePath, working.absolutePath, temp.absolutePath)
    }

    /**
     * Byte-for-byte SFA `Application.onCreate` order: no `Seq.setContext`,
     * first gomobile API is [Libbox.setLocale] (Seq clinit loads `box`),
     * then [Libbox.setup]. [context] must be Application.
     */
    private class GomobileBridge(
        private val context: Context,
        private val basePath: String,
        private val workingPath: String,
        private val tempPath: String,
    ) : NativeBridge {
        override fun setLocale() {
            val locale = Locale.getDefault().toLanguageTag()
            CrashTrail.mark("native: before Libbox.setLocale $locale")
            Libbox.setLocale(locale)
            CrashTrail.mark("native: after Libbox.setLocale")
        }

        override fun setup() {
            CrashTrail.mark("native: before SetupOptions (after setLocale, no setContext)")
            val options = SetupOptions()
            options.basePath = basePath
            options.workingPath = workingPath
            options.tempPath = tempPath
            val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val stack = FixAndroidStackPolicy.enabled(Build.VERSION.SDK_INT, debug)
            options.fixAndroidStack = stack
            CrashTrail.mark(
                "native: before Libbox.setup fixAndroidStack=$stack sdk=${Build.VERSION.SDK_INT} debug=$debug",
            )
            Libbox.setup(options)
            CrashTrail.mark("native: Libbox.setup returned")
        }

        override fun version(): String {
            CrashTrail.mark("native: before Libbox.version")
            return runCatching { Libbox.version() }.getOrElse { PINNED_VERSION }
        }
    }

    private data class SetupPaths(
        val base: String,
        val working: String,
        val temp: String,
    )

    private const val TAG = "LerNet.Native"
}
