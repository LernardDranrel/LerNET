package app.lernet.ui.importcfg

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private fun draw(text: String, size: Int = 400): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    }

    @Test
    fun readsVlessLinkFromPicture() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.org:443?security=reality&sni=example.org#Мой сервер"
        assertEquals(link, QrDecoder.decode(draw(link), 400, 400))
    }

    @Test
    fun blankPictureHasNoCode() {
        assertNull(QrDecoder.decode(IntArray(200 * 200) { 0xFFFFFFFF.toInt() }, 200, 200))
    }
}
