package app.lernet.engine.policy

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowInspectionTest {
    private fun parse(value: String) = FlowInspection.fromNative(Json.parseToJsonElement(value) as JsonObject)

    @Test fun readsBoundedPreviewAndLatestTransfers() {
        val preview = Base64.getEncoder().encodeToString("GET / HTTP/1.1\r\n\r\nhello".toByteArray())
        val view = parse(
            """{"inspection":{"transfer_count":2,"payload_available":true,"upload_prefix":"$preview",
            "transfers":[{"sequence":1,"at_ms":1000,"upload":true,"bytes":20},
            {"sequence":2,"at_ms":1100,"upload":false,"bytes":30}]}}"""
        )!!
        assertEquals(2L, view.transfers.first().sequence)
        assertTrue(view.text(true).contains("hello"))
        assertFalse(view.encrypted(null))
        assertEquals(2L, view.transferCount)
    }

    @Test fun rejectsOversizedAndMalformedPayloadWithoutClaimingEncryption() {
        val huge = Base64.getEncoder().encodeToString(ByteArray(513))
        val view = parse("""{"inspection":{"transfer_count":0,"upload_prefix":"$huge","download_prefix":"%%%"}}""")!!
        assertTrue(view.bytes(true).isEmpty())
        assertTrue(view.bytes(false).isEmpty())
        assertFalse(view.encrypted(null))
        assertNull(parse("""{"inspection":{"transfer_count":"0"}}"""))
    }

    @Test fun detectsTlsButDoesNotLabelArbitraryBinaryEncrypted() {
        assertTrue(FlowInspection(Base64.getEncoder().encodeToString(byteArrayOf(22, 3, 3, 0, 10))).encrypted(null))
        assertTrue(FlowInspection().encrypted("quic"))
        assertFalse(FlowInspection(Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3))).encrypted(null))
    }

    @Test fun ignoresInvalidTimelineFactsAndBoundsTheTail() {
        val rows = (1..20).joinToString(",") { """{"sequence":$it,"at_ms":1000,"upload":true,"bytes":1}""" }
        val view = parse("""{"inspection":{"transfer_count":20,"transfers":[$rows]}}""")!!
        assertEquals(8, view.transfers.size)
        assertEquals(20L, view.transfers.first().sequence)
        assertEquals(13L, view.transfers.last().sequence)
    }
}
