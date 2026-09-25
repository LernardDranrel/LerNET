package app.lernet.engine.log

import java.io.File
import java.io.FileOutputStream

object DurableFile {
    fun write(target: File, text: String) {
        val parent = target.parentFile ?: error("durable write needs a parent directory: ${target.path}")
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        writeSynced(tmp, text)
        if (tmp.renameTo(target)) {
            return
        }
        writeSynced(target, text)
        tmp.delete()
    }

    private fun writeSynced(target: File, text: String) {
        FileOutputStream(target).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
    }
}
