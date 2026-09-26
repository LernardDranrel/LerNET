package app.lernet.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.util.concurrent.TimeUnit

internal data class WindowsAppEntry(
    val label: String,
    val executable: File,
    val running: Boolean = false,
    val installed: Boolean = false,
) {
    val processName: String get() = executable.name
}

/** The routing core matches Windows process names, while the catalog supplies human names and icons. */
internal object WindowsAppCatalog {
    fun load(): List<WindowsAppEntry> {
        val collected = runningApps() + installedApps() + startMenuApps()
        return collected.groupBy { it.executable.absolutePath.lowercase() }.values.map { matches ->
            val preferred = matches.firstOrNull { it.installed && it.label != it.processName } ?: matches.first()
            preferred.copy(running = matches.any { it.running }, installed = matches.any { it.installed })
        }.sortedWith(compareBy<WindowsAppEntry> { it.label.lowercase() }.thenBy { it.processName.lowercase() })
    }

    private fun runningApps(): List<WindowsAppEntry> = runCatching {
        ProcessHandle.allProcesses().use { stream ->
            stream.map { it.info().command().orElse(null) }
                .filter { it != null && it.endsWith(".exe", ignoreCase = true) }
                .map { File(it) }
                .filter { it.isFile }
                .map { WindowsAppEntry(it.nameWithoutExtension, it, running = true) }
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun installedApps(): List<WindowsAppEntry> {
        val roots = listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)
        val keys = listOf(
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        )
        return roots.flatMap { root -> keys.flatMap { key ->
            runCatching { Advapi32Util.registryGetKeys(root, key).toList() }.getOrDefault(emptyList()).mapNotNull { sub ->
                val path = "$key\\$sub"
                val label = registryString(root, path, "DisplayName")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val iconPath = registryString(root, path, "DisplayIcon")?.let(::executableFromIcon)
                val location = registryString(root, path, "InstallLocation")?.let(::File)
                val file = iconPath ?: location?.takeIf(File::isDirectory)?.listFiles()
                    ?.filter { it.isFile && it.extension.equals("exe", ignoreCase = true) &&
                        !it.name.contains("uninstall", ignoreCase = true) && !it.name.contains("update", ignoreCase = true) }
                    ?.singleOrNull()
                file?.takeIf(File::isFile)?.let { WindowsAppEntry(label, it, installed = true) }
            }
        } }
    }

    private fun registryString(root: WinReg.HKEY, key: String, name: String): String? = runCatching {
        Advapi32Util.registryGetStringValue(root, key, name)
    }.getOrNull()

    internal fun executableFromIcon(value: String): File? {
        val expanded = Regex("%([^%]+)%").replace(value) { match ->
            System.getenv(match.groupValues[1]) ?: match.value
        }
        val end = expanded.indexOf(".exe", ignoreCase = true)
        if (end < 0) return null
        return File(expanded.substring(0, end + 4).trim().trim('"')).takeIf(File::isFile)
    }

    private fun startMenuApps(): List<WindowsAppEntry> = runCatching {
        val script = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            ${'$'}paths = @("${'$'}env:APPDATA\Microsoft\Windows\Start Menu\Programs", "${'$'}env:ProgramData\Microsoft\Windows\Start Menu\Programs")
            ${'$'}shell = New-Object -ComObject WScript.Shell
            Get-ChildItem -LiteralPath ${'$'}paths -Filter *.lnk -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
                ${'$'}link = ${'$'}shell.CreateShortcut(${'$'}_.FullName)
                if (${'$'}link.TargetPath -match '\.exe${'$'}') { [Console]::Out.WriteLine(${'$'}_.BaseName + "`t" + ${'$'}link.TargetPath) }
            }
        """.trimIndent()
        val output = File.createTempFile("lernet-shortcuts", ".txt")
        try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching emptyList()
            }
            output.useLines { lines -> lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                File(parts[1]).takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
                    ?.let { WindowsAppEntry(parts[0], it, installed = true) }
            }.toList() }
        } finally {
            output.delete()
        }
    }.getOrDefault(emptyList())
}
