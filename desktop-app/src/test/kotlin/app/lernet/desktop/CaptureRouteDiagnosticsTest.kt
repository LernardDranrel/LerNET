package app.lernet.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRouteDiagnosticsTest {
    @Test
    fun `route failure exposes finite stage and numeric OS code`() {
        val body = Json.parseToJsonElement(
            """{"error":"expert_capture_route_update_failed","route_update_stage":"add_route","win32_code":87}"""
        ) as JsonObject
        assertEquals(" Этап: добавление маршрута. Код Windows: 87.", captureRouteFailureDetails(body))
    }

    @Test
    fun `unknown stage never leaks arbitrary provider text`() {
        val body = Json.parseToJsonElement(
            """{"error":"expert_capture_route_update_failed","route_update_stage":"private-token","win32_code":87}"""
        ) as JsonObject
        assertEquals("", captureRouteFailureDetails(body))
    }
}
