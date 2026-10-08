package app.lernet.engine.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertNativeStatusEvidenceTest {
    @Test fun `flow identities match the native positive int64 contract on both platforms`() {
        for (value in listOf("1", "17", "9223372036854775807")) {
            val row = Json.parseToJsonElement("{\"id\":$value}") as JsonObject
            assertEquals(value, ExpertNativeStatusEvidence.flowId(row))
        }
        for (value in listOf("0", "-1", "1.5", "9223372036854775808", "\"17\"", "true", "null", "{}", "[]")) {
            val row = Json.parseToJsonElement("{\"id\":$value}") as JsonObject
            assertEquals(null, ExpertNativeStatusEvidence.flowId(row))
        }
    }

    @Test fun `cleanup evidence requires actual numeric revision and accepts only finite reason codes`() {
        val body = Json.parseToJsonElement(
            """{"retired_cleanup_failures":[
            {"revision":0,"reason":"exit_stop_failed","exit_tags":["b","a","b",4]},
            {"revision":1,"reason":"provider secret text"},
            {"revision":-1,"reason":"generation_stop_failed"},
            {"revision":"2","reason":"generation_stop_failed"},
            {"revision":1.5,"reason":"generation_stop_failed"},null
        ]}"""
        ) as JsonObject
        assertEquals(
            listOf(
                ExpertRetiredCleanupFailure(0, "exit_stop_failed", listOf("a", "b")),
                ExpertRetiredCleanupFailure(1, "expert_cleanup_pending")
            ),
            ExpertNativeStatusEvidence.retiredCleanupFailures(body),
        )
    }

    @Test fun `cleanup parser bounds row count exit tags and string size`() {
        val rows = List(140) { revision ->
            JsonObject(
                mapOf(
                    "revision" to JsonPrimitive(revision),
                    "reason" to JsonPrimitive("generation_stop_failed"),
                    "exit_tags" to JsonArray(List(140) { JsonPrimitive("$it-" + "x".repeat(200)) }),
                )
            )
        }
        val failures = ExpertNativeStatusEvidence.retiredCleanupFailures(
            JsonObject(mapOf("retired_cleanup_failures" to JsonArray(rows))),
        )
        assertEquals(128, failures.size)
        assertEquals(128, failures.first().exitTags.size)
        assertTrue(failures.all { failure -> failure.exitTags.all { it.length <= 128 } })
    }
}
