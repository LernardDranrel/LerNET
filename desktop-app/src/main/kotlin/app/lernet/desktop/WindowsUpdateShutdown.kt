package app.lernet.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A per-installation signal: an updater cannot accidentally close another portable copy. */
internal object WindowsUpdateShutdown {
    fun eventName(executable: String): String {
        val canonical = Path.of(executable).toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "Local\\LerNET.Update.$hash"
    }

    fun listen(onShutdown: () -> Unit): AutoCloseable? {
        if (!System.getProperty("os.name").startsWith("Windows")) return null
        val executable = ProcessHandle.current().info().command().orElse("")
        if (!executable.substringAfterLast('\\').equals("LerNET.exe", true)) return null
        return runCatching {
            val kernel = Kernel32.INSTANCE
            val handle = kernel.CreateEvent(null, false, false, eventName(executable)) ?: return null
            val stopped = AtomicBoolean(false)
            val thread = Thread({
                try {
                    while (!stopped.get()) {
                        when (kernel.WaitForSingleObject(handle, 500)) {
                            WinBase.WAIT_OBJECT_0 -> { if (!stopped.get()) onShutdown(); break }
                            WinError.WAIT_TIMEOUT -> Unit
                            else -> break
                        }
                    }
                } finally { kernel.CloseHandle(handle) }
            }, "LerNET-update-shutdown").apply { isDaemon = true; start() }
            AutoCloseable { stopped.set(true); if (Thread.currentThread() != thread) thread.join(1500) }
        }.getOrNull()
    }
}
