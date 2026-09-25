package app.lernet.engine.log

import app.lernet.engine.redact.LerNetLog

fun interface CrashTrailSink {
    fun onCrumb(step: String)
}

fun interface CrashFailureSink {
    fun onFailure(site: String, error: Throwable)
}

object CrashTrail {
    const val TAG = "LerNet.Trail"

    @Volatile
    var persistCrumb: CrashTrailSink? = null

    @Volatile
    var persistFailure: CrashFailureSink? = null

    fun mark(step: String) {
        LerNetLog.i(TAG, step)
        runCatching { persistCrumb?.onCrumb(step) }
            .onFailure { error ->
                LerNetLog.e(TAG, "crumb persist failed: ${error.message}", error)
            }
    }

    fun recordFailure(site: String, error: Throwable) {
        mark("FAILURE $site ${error.javaClass.name}: ${error.message}")
        runCatching { persistFailure?.onFailure(site, error) }
            .onFailure { persistError ->
                LerNetLog.e(TAG, "failure persist failed: ${persistError.message}", persistError)
            }
    }
}
