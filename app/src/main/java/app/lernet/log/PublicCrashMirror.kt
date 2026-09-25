package app.lernet.log

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import app.lernet.engine.log.DurableFile
import app.lernet.engine.redact.LerNetLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object PublicCrashMirror {
    const val DOWNLOAD_CRUMB = "last-crumb.txt"
    const val DOWNLOAD_CRASH = "crash-report.txt"
    const val DOWNLOAD_CRASH_LAST = "crash-last.txt"
    const val DOWNLOAD_RING = "session-ring.log"
    const val DOWNLOAD_SUBDIR = "LerNET"

    private val downloadUris = ConcurrentHashMap<String, Uri>()

    fun writeFile(publicDir: File?, name: String, text: String) {
        if (publicDir == null) return
        runCatching {
            publicDir.mkdirs()
            DurableFile.write(File(publicDir, name), text)
            val nested = File(publicDir, DOWNLOAD_SUBDIR)
            nested.mkdirs()
            DurableFile.write(File(nested, name), text)
        }.onFailure { error ->
            LerNetLog.w(TAG, "public $name: ${error.message}", error)
        }
    }

    fun upsertDownload(context: Context, displayName: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            upsertDownloadQ(context, displayName, text)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun upsertDownloadQ(context: Context, displayName: String, text: String) {
        runCatching {
            val resolver = context.contentResolver
            val uri = downloadUris[displayName] ?: findOrInsert(context, displayName)
            downloadUris[displayName] = uri
            val pfd = resolver.openFileDescriptor(uri, "wt")
                ?: error("MediaStore openFileDescriptor returned null for $displayName")
            pfd.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    stream.fd.sync()
                }
            }
        }.onFailure { error ->
            downloadUris.remove(displayName)
            LerNetLog.w(TAG, "mediastore $displayName: ${error.message}", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findOrInsert(context: Context, displayName: String): Uri {
        val resolver = context.contentResolver
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0),
                )
            } else {
                null
            }
        }
        if (existing != null) {
            return existing
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath())
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null for $displayName")
    }

    private fun relativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/"

    private const val TAG = "LerNet.CrashMirror"
}
