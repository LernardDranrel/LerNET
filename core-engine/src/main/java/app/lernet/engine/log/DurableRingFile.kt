package app.lernet.engine.log

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Append-only ring on disk. Callers choose whether each append is synced;
 * concurrent writes and rotation are serialized.
 */
class DurableRingFile(
    private val file: File,
    private var maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    @Synchronized
    fun applyCap(nextMax: Int) {
        maxBytes = nextMax
        if (file.exists() && file.length() > maxBytes) {
            rotate()
        }
    }

    @Synchronized
    fun append(line: String, sync: Boolean = true) {
        val parent = file.parentFile ?: error("ring file needs a parent: ${file.path}")
        parent.mkdirs()
        val payload = if (line.endsWith("\n")) line else "$line\n"
        FileOutputStream(file, true).use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
            if (sync) {
                stream.fd.sync()
            }
        }
        if (file.length() > maxBytes) {
            rotate()
        }
    }

    /** A small recent window for UI and mirrors; the full journal stays on disk. */
    @Synchronized
    fun readText(maxReadBytes: Int = DEFAULT_READ_MAX_BYTES): String {
        require(maxReadBytes > 0)
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - maxReadBytes.toLong()).coerceAtLeast(0L)
            input.seek(start)
            if (start > 0L) skipPartialLine(input)
            val count = (input.length() - input.filePointer).coerceAtMost(maxReadBytes.toLong()).toInt()
            val bytes = ByteArray(count)
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    /** Stream the complete journal for sharing without allocating its full size. */
    @Synchronized
    fun copyTo(output: OutputStream) {
        if (file.exists()) file.inputStream().use { it.copyTo(output) }
    }

    private fun rotate() {
        val parent = file.parentFile ?: error("ring file needs a parent: ${file.path}")
        val tmp = File(parent, "${file.name}.tmp")
        RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - maxBytes.toLong() * 3 / 4).coerceAtLeast(0L)
            input.seek(start)
            if (start > 0L) skipPartialLine(input)
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.inputStream().use { source ->
                FileOutputStream(file).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            tmp.delete()
        }
    }

    private fun skipPartialLine(input: RandomAccessFile) {
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) return
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_READ_MAX_BYTES = 1024 * 1024
    }
}
