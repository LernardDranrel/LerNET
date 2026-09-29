package app.lernet.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.filechooser.FileSystemView
import org.jetbrains.skia.Image
import com.sun.jna.platform.win32.Kernel32

internal data class ProcessIdentity(
    val label: String,
    val icon: ImageBitmap?,
    val parent: String? = null,
)

/** Resolves names and executable icons only when the core supplies a process path. */
internal object WindowsProcessIdentity {
    private val cache = ConcurrentHashMap<String, ProcessIdentity>()
    private val helperNames = setOf("msedgewebview2.exe", "webview2.exe", "java.exe", "javaw.exe")
    private val deviceRoots by lazy {
        File.listRoots().mapNotNull { drive ->
            val dosName = drive.path.trimEnd('\\')
            val buffer = CharArray(1024)
            val count = Kernel32.INSTANCE.QueryDosDevice(dosName, buffer, buffer.size)
            if (count <= 0) null else String(buffer, 0, count).substringBefore('\u0000') to dosName
        }
    }

    fun resolveObservation(path: String): ProcessIdentity? {
        if (!System.getProperty("os.name").startsWith("Windows")) return null
        val allowed = app.lernet.desktop.observation.ObservationIconPolicy.allows(path,
            isFixedDrive = { root -> Kernel32.INSTANCE.GetDriveType(root) == 3 },
            isPlainLocalEntry = { entry ->
                val attributes = Kernel32.INSTANCE.GetFileAttributes(entry)
                attributes != -1 && attributes and 0x400 == 0
            })
        if (!allowed) return null
        return cache.computeIfAbsent("observation:$path") { ProcessIdentity(File(path).nameWithoutExtension, readIcon(File(path))) }
    }

    fun resolve(pathOrName: String): ProcessIdentity {
        if (pathOrName.isBlank()) return ProcessIdentity("Процесс не определён ядром", null)
        return cache.computeIfAbsent(pathOrName) { source -> resolveUncached(source) }
    }

    private fun resolveUncached(source: String): ProcessIdentity {
        val normalized = deviceRoots.firstOrNull { (device, _) -> source.startsWith(device, ignoreCase = true) }
            ?.let { (device, drive) -> drive + source.substring(device.length) } ?: source
        val suppliedFile = File(normalized)
        val matchingProcess = runCatching {
            ProcessHandle.allProcesses().use { handles ->
                val matches = handles.filter { handle ->
                    handle.info().command().orElse("").let { command ->
                        command.equals(normalized, ignoreCase = true) ||
                            (!suppliedFile.isFile && File(command).name.equals(suppliedFile.name, ignoreCase = true))
                    }
                }.limit(2).toList()
                matches.singleOrNull()
            }
        }.getOrNull()
        val directPath = suppliedFile.takeIf(File::isFile)?.absolutePath
            ?: matchingProcess?.info()?.command()?.orElse(null)
        val parentPath = matchingProcess?.parent()?.orElse(null)?.info()?.command()?.orElse(null)
        val directName = directPath?.let { File(it).name } ?: suppliedFile.name
        val parentFile = parentPath?.let(::File)?.takeIf(File::isFile)
        val useParent = directName.lowercase() in helperNames && parentFile != null &&
            parentFile.name.lowercase() !in setOf("explorer.exe", "services.exe", "svchost.exe")
        val displayFile = if (useParent) parentFile else directPath?.let(::File)
        val label = displayFile?.nameWithoutExtension?.takeIf(String::isNotBlank) ?: directName
        return ProcessIdentity(label, displayFile?.let(::readIcon),
            parentFile?.nameWithoutExtension?.takeIf { it != label })
    }

    private fun readIcon(file: File): ImageBitmap? = runCatching {
        val icon = FileSystemView.getFileSystemView().getSystemIcon(file)
        val bitmap = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = bitmap.createGraphics()
        try { icon.paintIcon(null, graphics, 0, 0) } finally { graphics.dispose() }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
