package com.openminis.app.tools

/** Core tool contracts and Android workspace paths. */
object CoreToolNames {
    fun isMutation(name: String) = name in setOf("write", "edit")
    /** Image is a visual category of read, not a separately callable tool. */
    fun presentation(name: String?, hasImage: Boolean = false): String? = if (name == "read" && hasImage) "image" else name
    fun editPreview(args: org.json.JSONObject, field: String): String {
        val edits = args.optJSONArray("edits") ?: return ""
        return (0 until edits.length()).joinToString("\n") { edits.optJSONObject(it)?.optString(field).orEmpty() }
    }
    /** Core-only validation: Android tools retain their existing flexible input contracts. */
    fun validate(args: org.json.JSONObject, definition: com.openminis.app.data.model.AgentToolDefinition): String? {
        fun check(value: Any?, p: com.openminis.app.data.model.AgentToolParam, path: String): String? {
            val valid = when (p.type) {
                "string" -> value is String
                "boolean" -> value is Boolean
                "number" -> value is Number && value.toDouble().isFinite()
                "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() == kotlin.math.floor(value.toDouble())
                "array" -> value is org.json.JSONArray
                "object" -> value is org.json.JSONObject
                else -> true
            }
            if (!valid) return "$path must be ${p.type}"
            if (p.enumValues != null && value !in p.enumValues) return "$path must be one of ${p.enumValues.joinToString()}"
            if (value is org.json.JSONArray && p.items != null) for (i in 0 until value.length()) {
                check(value.opt(i), p.items, "$path[$i]")?.let { return it }
            }
            if (value is org.json.JSONObject) {
                for (key in p.required.orEmpty()) if (!value.has(key) || value.isNull(key)) return "$path.$key is required"
                for ((key, child) in p.properties.orEmpty()) if (value.has(key)) check(value.opt(key), child, "$path.$key")?.let { return it }
            }
            return null
        }
        for ((key, p) in definition.parameters) {
            if (!args.has(key) || (args.isNull(key) && key !in definition.required)) continue
            check(args.opt(key), p, key)?.let { return "Tool '${definition.name}': $it" }
        }
        return null
    }
    fun linuxPath(path: String): String = when {
        path.startsWith("minis://") -> "/var/minis/" + java.net.URLDecoder.decode(path.removePrefix("minis://"), "UTF-8")
        path == "~" -> "/root"
        path.startsWith("~/") -> "/root/" + path.removePrefix("~/")
        path.startsWith("/") -> path
        else -> "/var/minis/workspace/$path"
    }
}
