package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/** Pi custom entries on Android's message-backed transcript. Never model/UI content. */
object CodemodeStore {
    const val TYPE = "codemode-store"

    fun isEntry(partsJson: String): Boolean = partsJson.contains(TYPE) &&
        runCatching { JSONArray(partsJson).optJSONObject(0)?.optString("type") == TYPE }.getOrDefault(false)

    /** Chunk metadata so even escaped 1 MiB state never exceeds a SQLite CursorWindow/row cap. */
    fun encode(writes: String, id: String): List<String> {
        val chunks = writes.chunked(32_000)
        return chunks.mapIndexed { index, chunk -> JSONArray().put(JSONObject().put("type", TYPE)
            .put("value", JSONObject().put("id", id).put("index", index).put("count", chunks.size).put("chunk", chunk))).toString() }
    }

    fun read(transcript: Iterable<String>): JSONObject {
        val store = JSONObject()
        val pending = mutableMapOf<String, MutableMap<Int, String>>()
        for (parts in transcript) {
            if (!isEntry(parts)) continue
            val data = JSONArray(parts).getJSONObject(0).getJSONObject("value")
            val id = data.getString("id")
            val entries = pending.getOrPut(id) { mutableMapOf() }
            entries[data.getInt("index")] = data.getString("chunk")
            val count = data.getInt("count")
            if (entries.size == count && (0 until count).all { it in entries }) {
                CodemodeTool.applyWrites(store, (0 until count).joinToString("") { entries.getValue(it) })
                pending.remove(id)
            }
        }
        return store
    }
}
