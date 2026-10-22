package app.lernet.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/** QR from a picture — same idea as Android ImportSources.QrDecoder (ZXing core, no network). */
object DesktopQr {
    fun decodeFile(file: Path): String? {
        val image = runCatching { ImageIO.read(file.toFile()) }.getOrNull() ?: return null
        return decode(image)
    }

    fun decode(image: BufferedImage): String? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width)
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
