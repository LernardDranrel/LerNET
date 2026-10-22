package app.lernet.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * «Запускать при входе в систему»: a Task Scheduler task for the current user, started at sign-in with `--autostart`.
 * A task (not the Run key) because LerNET runs the VPN with administrator rights: created from an elevated LerNET it starts
 * elevated without a UAC prompt at every sign-in. The installer removes it on uninstall.
 */
object WindowsAutostart {
    const val TASK_NAME = "LerNET\\Autostart"
    const val ARGUMENT = "--autostart"

    sealed interface Outcome {
        data object Done : Outcome
        /** Not a packaged LerNET.exe (development run): nothing to register. */
        data object NotInstalled : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun taskXml(executable: Path, userId: String, elevated: Boolean): String {
        val user = xml(userId)
        return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>LerNET: запуск при входе в систему</Description></RegistrationInfo>
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>$user</UserId></LogonTrigger></Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$user</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>${if (elevated) "HighestAvailable" else "LeastPrivilege"}</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${xml(executable.toString())}</Command>
      <Arguments>$ARGUMENT</Arguments>
      <WorkingDirectory>${xml(executable.toString().substringBeforeLast('\\', ""))}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""
    }

    /** Make Windows match the setting. Runs schtasks; call off the UI thread. */
    fun sync(enabled: Boolean, elevated: Boolean = WindowsElevation.isElevated): Outcome {
        if (!System.getProperty("os.name").startsWith("Windows")) return Outcome.NotInstalled
        val executable = packagedExecutable() ?: return Outcome.NotInstalled
        val exists = schtasks("/Query", "/TN", TASK_NAME) == 0
        if (!enabled) {
            if (!exists || schtasks("/Delete", "/TN", TASK_NAME, "/F") == 0) return Outcome.Done
            return Outcome.Failed("Не удалось выключить запуск при входе в систему. Запустите LerNET от имени администратора и повторите.")
        }
        // A standard LerNET cannot replace a task an elevated LerNET made; it only adds a missing one.
        if (exists && !elevated) return Outcome.Done
        val user = listOfNotNull(System.getenv("USERDOMAIN"), System.getenv("USERNAME")).joinToString("\\")
        val file = Files.createTempFile("lernet-autostart", ".xml")
        return try {
            Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + taskXml(executable, user, elevated).toByteArray(StandardCharsets.UTF_16LE))
            if (schtasks("/Create", "/TN", TASK_NAME, "/XML", file.toString(), "/F") == 0) Outcome.Done
            else Outcome.Failed("Не удалось включить запуск при входе в систему: Windows отказал в создании задачи.")
        } finally {
            runCatching { Files.deleteIfExists(file) }
        }
    }

    private fun packagedExecutable(): Path? {
        val command = ProcessHandle.current().info().command().orElse("")
        if (command.isBlank()) return null
        val executable = Path.of(command).toAbsolutePath().normalize()
        return executable.takeIf { it.fileName.toString().equals("LerNET.exe", ignoreCase = true) && Files.isRegularFile(it) }
    }

    private fun schtasks(vararg args: String): Int = runCatching {
        val process = ProcessBuilder(listOf("schtasks.exe") + args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            -1
        } else process.exitValue()
    }.getOrDefault(-1)

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
