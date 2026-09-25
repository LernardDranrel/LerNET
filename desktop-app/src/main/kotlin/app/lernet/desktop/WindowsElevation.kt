package app.lernet.desktop

import app.lernet.engine.RunMode
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path

/** A saved VPN choice remains intact; a standard Windows process uses the local proxy. */
object DesktopRunMode {
    fun effective(requested: String, elevated: Boolean): RunMode =
        if (!elevated) RunMode.PROXY
        else runCatching { RunMode.valueOf(requested) }.getOrDefault(RunMode.PROXY)
}

/** Startup preference follows the selected profile override, then the global default. */
object DesktopStartup {
    fun needsElevation(saved: StoredState, elevated: Boolean): Boolean {
        if (elevated) return false
        val selected = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
        return (selected?.modeOverride ?: saved.mode) == RunMode.FULL_VPN.name
    }
}

/** Checks the process token and requests UAC when full VPN is selected. */
object WindowsElevation {
    val isElevated: Boolean by lazy {
        runCatching { readTokenElevation() }.getOrDefault(false)
    }

    fun relaunchAsAdministrator(): Result<Unit> = runCatching {
        check(!isElevated) { "LerNET уже запущен от имени администратора" }
        val command = ProcessHandle.current().info().command().orElse("")
        val executable = Path.of(command).toAbsolutePath().normalize()
        check(executable.fileName.toString().equals("LerNET.exe", ignoreCase = true) && Files.isRegularFile(executable)) {
            "Запрос прав доступен в собранном LerNET.exe"
        }
        val result = Shell32.INSTANCE.ShellExecute(null, "runas", executable.toString(), null,
            executable.parent.toString(), 1).toLong()
        check(result > 32) {
            if (result == 5L) "Запрос прав администратора отклонён. Продолжаем в режиме локального прокси."
            else "Не удалось запросить права администратора (код $result)"
        }
    }

    @Structure.FieldOrder("tokenIsElevated")
    class TokenElevation : Structure() {
        @JvmField var tokenIsElevated: Int = 0
    }

    private fun readTokenElevation(): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val token = WinNT.HANDLEByReference()
        check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            "Не удалось открыть токен процесса"
        }
        try {
            val elevation = TokenElevation()
            check(Advapi32.INSTANCE.GetTokenInformation(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenElevation,
                elevation, elevation.size(), IntByReference())) {
                "Не удалось проверить права администратора"
            }
            return elevation.tokenIsElevated != 0
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.value)
        }
    }
}
