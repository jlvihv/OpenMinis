package com.openminis.app.tools

import com.openminis.app.data.repository.ChatRepository
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CodemodeStoreTest {
    @Test fun customEntriesAreNotModelMessages() {
        val encoded = CodemodeStore.encode("[[\"x\",\"1\"]]", "first")
        assertTrue(encoded.all { CodemodeStore.isEntry(it) })
        assertTrue(encoded.all { ChatRepository.isEmptyAssistantCarrier("system", it) })
        assertFalse(CodemodeStore.isEntry("[{\"type\":\"text\",\"value\":\"codemode-store\"}]"))
        assertEquals("1", CodemodeStore.read(encoded).getString("x"))
    }

    @Test fun branchAndRewindSeeOnlyEntriesOnTheirPath() {
        val root = CodemodeStore.encode("[[\"x\",\"1\"]]", "root")
        val branchA = root + CodemodeStore.encode("[[\"x\",\"2\"]]", "a")
        val branchB = root + CodemodeStore.encode("[[\"x\"]]", "b")
        assertEquals("1", CodemodeStore.read(root).getString("x"))
        assertEquals("2", CodemodeStore.read(branchA).getString("x"))
        assertFalse(CodemodeStore.read(branchB).has("x"))
    }

    @Test fun largeEscapedStateIsChunkedWithoutChangingJson() {
        val json = org.json.JSONObject.quote("\"\\\n中文🌍".repeat(15000))
        val writes = JSONArray().put(JSONArray().put("value").put(json)).toString()
        val encoded = CodemodeStore.encode(writes, "large")
        assertTrue(encoded.size > 1)
        assertTrue(encoded.all { it.length < ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH })
        assertEquals(json, CodemodeStore.read(encoded).getString("value"))
        assertFalse(CodemodeStore.read(encoded.dropLast(1)).has("value"))
    }
}
