package app.lernet.ui.importcfg

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * Audit N16: a config can come from a file or from a picture with a QR code.
 * Everything stays on the phone: the file is read through the system picker (no storage permission),
 * the QR code is read by ZXing core — a plain Java library, no network and no Google Play services.
 */
internal object ImportSources {
    /** Config files are text; a larger file is almost surely the wrong one. */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    sealed interface Result {
        data class Text(val value: String) : Result
        data object TooLarge : Result
        data object Unreadable : Result
        data object NoQr : Result
    }

    fun readText(resolver: ContentResolver, uri: Uri): Result = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) return Result.TooLarge
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
            if (text.isEmpty() || text.any { it == '\u0000' }) Result.Unreadable else Result.Text(text)
        } ?: Result.Unreadable
    }.getOrDefault(Result.Unreadable)

    fun readQr(resolver: ContentResolver, uri: Uri): Result = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Unreadable
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return Result.Unreadable
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val text = QrDecoder.decode(pixels, bitmap.width, bitmap.height)
        bitmap.recycle()
        if (text.isNullOrBlank()) Result.NoQr else Result.Text(text.trim())
    }.getOrDefault(Result.Unreadable)

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** Pure part, checked by a JVM test with a QR code drawn by ZXing itself. */
internal object QrDecoder {
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, argb)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try {
                return MultiFormatReader().decode(bitmap, hints).text
            } catch (_: NotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }
}
