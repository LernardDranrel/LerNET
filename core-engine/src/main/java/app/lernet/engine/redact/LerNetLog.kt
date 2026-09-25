package app.lernet.engine.redact

import android.util.Log
import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.log.LogRingBuffer

interface LogPersist {
    fun onLine(line: String) = Unit
}

object LerNetLog {
    val buffer: LogRingBuffer = LogRingBuffer()

    @Volatile
    var persist: LogPersist? = null

    fun i(tag: String, message: String) {
        write(Log.INFO, "I", tag, message, error = null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        write(Log.WARN, "W", tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        write(Log.ERROR, "E", tag, message, error)
    }

    private fun write(
        androidLevel: Int,
        level: String,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val safe = SecretRedactor.redact(message)
        buffer.append(level, tag, safe)
        persist?.onLine("$level $tag $safe")
        if (error != null) {
            val stack = SecretRedactor.redact(error.stackTraceToString())
            buffer.append(level, tag, stack)
            persist?.onLine("$level $tag $stack")
        }
        when {
            error == null -> Log.println(androidLevel, tag, safe)
            else -> Log.println(androidLevel, tag, "$safe\n${SecretRedactor.redact(error.stackTraceToString())}")
        }
    }
}
