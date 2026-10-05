package app.lernet.engine.expert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Native diagnostics are untrusted fields; parser exceptions must never disclose their raw JSON. */
internal object ExpertNativeJson {
    fun objectValue(raw: String, maximumLength: Int, label: String): JsonObject {
        require(raw.length <= maximumLength) { "$label exceeds bounds" }
        return try {
            Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalStateException()
        } catch (_: Exception) {
            throw IllegalStateException("$label is not a valid object")
        }
    }

    fun string(body: JsonObject, key: String): String? =
        (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun long(body: JsonObject, key: String): Long? =
        (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    fun int(body: JsonObject, key: String): Int? =
        (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    fun boolean(body: JsonObject, key: String): Boolean? =
        (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    fun strings(value: JsonElement?, limit: Int): List<String> =
        (value as? JsonArray).orEmpty().asSequence().take(limit).mapNotNull {
            (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
        }.toList()
}
