package app.lernet.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import app.lernet.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LogShare {
    private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun shareSession(context: Context, store: AppLogStore) {
        shareScope.launch {
            val prepared = runCatching { store.flushShareBundle() }
            withContext(Dispatchers.Main) {
                prepared.fold(
                    onSuccess = { file -> shareOrReport(context, file, context.getString(R.string.share_logs)) },
                    onFailure = { showFailure(context) },
                )
            }
        }
    }

    fun shareTextFile(context: Context, text: String, name: String, title: String) {
        shareScope.launch {
            val prepared = runCatching {
                File(context.cacheDir, name).apply { writeText(text) }
            }
            withContext(Dispatchers.Main) {
                prepared.fold(
                    onSuccess = { file -> shareOrReport(context, file, title) },
                    onFailure = { showFailure(context) },
                )
            }
        }
    }

    fun shareCrash(context: Context, file: File) {
        shareOrReport(context, file, context.getString(R.string.share_crash))
    }

    private fun shareOrReport(context: Context, file: File, title: String) {
        runCatching { shareFile(context, file, title) }.onFailure { showFailure(context) }
    }

    private fun showFailure(context: Context) {
        Toast.makeText(context, R.string.logs_share_failed, Toast.LENGTH_SHORT).show()
    }

    private fun shareFile(context: Context, file: File, title: String) {
        require(file.exists()) { "Share file is missing" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    }
}
