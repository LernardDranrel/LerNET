package app.lernet.engine.compile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object PipeAffinity {
    fun tags(names: List<String>, reserved: Set<String> = emptySet()): Map<String, String> {
        val used = reserved.toHashSet()
        val out = linkedMapOf<String, String>()
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            var tag = tag(name)
            var suffix = 2
            while (!used.add(tag)) {
                tag = tag(name) + "-$suffix"
                suffix += 1
            }
            out[name] = tag
        }
        return out
    }

    fun clone(source: JsonObject, tag: String, concurrency: String = XhttpMode.MUX_CONCURRENCY): XhttpNormalizeResult {
        val copied = JsonObject(source.toMutableMap().apply { put("tag", JsonPrimitive(tag)) })
        return XhttpMode.normalize(copied, concurrency)
    }

    fun tag(name: String): String {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return "pipe-" + slug.ifBlank { "unnamed" }
    }
}
