package app.lernet.log

import android.content.Context
import android.net.Uri
import android.widget.Toast
import app.lernet.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Сохранить журнал»: writes the redacted session bundle into a file the user chose
 * (CreateDocument / system save). Nothing is sent to another app.
 */
object LogShare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Suggested file name for CreateDocument. */
    const val SUGGESTED_NAME = "LerNET-journal.txt"

    fun saveSession(context: Context, store: AppLogStore, uri: Uri) {
        scope.launch {
            val prepared = runCatching { store.flushShareBundle() }
            withContext(Dispatchers.Main) {
                prepared.fold(
                    onSuccess = { file ->
                        runCatching { writeFile(context, uri, file) }
                            .onSuccess {
                                Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { showFailure(context) }
                    },
                    onFailure = { showFailure(context) },
                )
            }
        }
    }

    fun saveText(context: Context, text: String, uri: Uri) {
        scope.launch {
            withContext(Dispatchers.Main) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }.onSuccess {
                    Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                }.onFailure { showFailure(context) }
            }
        }
    }

    private fun writeFile(context: Context, uri: Uri, file: File) {
        require(file.exists()) { "Journal file is missing" }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: error("no stream")
    }

    private fun showFailure(context: Context) {
        Toast.makeText(context, R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
    }
}
