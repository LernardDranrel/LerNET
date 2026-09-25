package app.lernet.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Install the exact, checked release bytes beside the working configuration. */
object BundledCore {
    private val files = mapOf(
        "sing-box.exe" to "29722313BCDD8F8D2EFCBC2B9C4DE1AE0EACE89E856F0785D703E7F2FE079DE1",
        "libcronet.dll" to "3217C6260FBCA5F16072E0B79735742F40109A63BB0FF88FD6B96DD6B54A2928",
        "wintun.dll" to "E5DA8447DC2C320EDC0FC52FA01885C103DE8C118481F683643CACC3220DAFCE",
    )

    fun install(directory: Path): Path {
        Files.createDirectories(directory)
        files.forEach { (name, expected) ->
            val target = directory.resolve(name)
            if (Files.isRegularFile(target) && sha256(target) == expected) return@forEach
            val stream = javaClass.getResourceAsStream("/runtime/$name")
                ?: error("В установщике отсутствует $name")
            val temp = directory.resolve("$name.part")
            stream.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            check(sha256(temp) == expected) { "Контрольная сумма $name не совпала" }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return directory.resolve("sing-box.exe")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
