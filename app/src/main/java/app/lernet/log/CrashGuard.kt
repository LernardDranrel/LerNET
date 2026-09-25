package app.lernet.log

import android.os.Looper
import android.os.Process
import app.lernet.engine.redact.LerNetLog
import kotlin.system.exitProcess

class CrashGuard(
    private val store: AppLogStore,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching {
            LerNetLog.e(TAG, "uncaught on ${thread.name}: ${error.message}", error)
            store.writeCrash(thread, error)
        }
        val chained = previous
        if (chained != null && chained !== this) {
            chained.uncaughtException(thread, error)
            return
        }
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    companion object {
        private const val TAG = "LerNet.Crash"

        fun install(store: AppLogStore) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            val guard = CrashGuard(store, current)
            Thread.setDefaultUncaughtExceptionHandler(guard)
            runCatching { Looper.getMainLooper().thread.uncaughtExceptionHandler = guard }
        }
    }
}
