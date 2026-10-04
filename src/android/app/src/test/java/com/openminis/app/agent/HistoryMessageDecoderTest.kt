package com.openminis.app.agent

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.storage.PastedMedia
import org.junit.Assert.*
import org.junit.Test

class HistoryMessageDecoderTest {
    private val bytes = byteArrayOf(1, 2, 3)
    private val decoder = HistoryMessageDecoder(object : HistoryMessageDecoder.Media {
        override fun pastedText(relativePath: String) = if (relativePath == "paste") "pasted" else null
        override fun userImage(relativePath: String, mimeType: String, linuxPath: String?) =
            HistoryMessageDecoder.UserImage(LLMMessage.ImagePart(bytes, "image/png", linuxPath, "no vision"), "resize note")
        override fun toolImage(linuxPath: String, mimeType: String) =
            if (linuxPath == "missing") null else AgentContentPart.ImageData(bytes, mimeType, linuxPath)
    })
    private fun row(json: String, role: String = "user") = MessageEntity("row", "session", role, json, 0, sortOrder = 0, reasoningContent = "reason")

    @Test fun replayKeepsCaptionPartOrderingAndMediaSemantics() {
        val json = """[
            {"type":"text","value":"caption"},
            {"type":"text","value":"<user-attached-files>inventory</user-attached-files>"},
            {"type":"mediaRef","value":{"relativePath":"ordinary","mimeType":"text/plain","originalFileName":"real.txt"}},
            {"type":"mediaRef","value":{"relativePath":"paste","mimeType":"text/plain","originalFileName":"Pasted#1.txt"}},
            {"type":"mediaRef","value":{"relativePath":"missing","mimeType":"text/plain","originalFileName":"Pasted#2.txt"}},
            {"type":"mediaRef","value":{"relativePath":"image","mimeType":"image/jpeg","linuxPath":"/uploads/image"}}
        ]"""
        val message = decoder.decode(row(json))!!
        assertEquals("captionpasted${PastedMedia.MISSING_PLACEHOLDER}", message.content)
        assertEquals(6, message.contentParts.size)
        assertEquals("row", message.dbMessageId)
        assertEquals("reason", message.reasoningContent)
        assertArrayEquals(bytes, message.imageParts.single().data)
        val image = message.contentParts.filterIsInstance<AgentContentPart.ImageData>().single()
        assertEquals("/uploads/image", image.linuxPath)
        assertEquals("no vision", image.noVisionPlaceholder)
        assertEquals(AgentContentPart.Text("resize note"), message.contentParts.last())
        assertNull(decoder.decode(row("""[{"type":"request-usage"}]""", "usage")))
        assertTrue(decoder.decode(row(RuntimeContextSnapshot.encode("facts")))!!.isRuntimeContext)
    }

    @Test fun replayPreservesToolSignaturesMultipleImagesAndMalformedFallback() {
        val json = """[
            {"type":"toolUse","value":{"toolUseId":"call|fc","name":"read","input":"{\"path\":\"a\"}","thoughtSignature":"signature"}},
            {"type":"toolResult","value":{"toolUseId":"call|fc","name":"read","output":"out","success":false,"detailsJson":"{}",
                "images":[{"path":"first","mimeType":"image/png"},{"path":"missing","mimeType":"image/png"},{"path":"second","mimeType":"image/png"}]}}
        ]"""
        val message = decoder.decode(row(json, "assistant"))!!
        val use = message.contentParts[0] as AgentContentPart.ToolUse
        assertEquals("signature", use.thoughtSignature)
        assertEquals("a", use.input.getString("path"))
        val result = message.contentParts[1] as AgentContentPart.ToolResult
        assertTrue(result.isError)
        assertEquals("first", result.imageLinuxPath)
        assertArrayEquals(bytes, result.imageData)
        assertEquals("second", (message.contentParts[2] as AgentContentPart.ImageData).linuxPath)
        assertEquals("{}", result.detailsJson)
        assertEquals(LLMMessage.Role.ASSISTANT, message.role)
        val malformed = """[{"type":"text","value":"prefix"},{"type":"toolUse","value":false}]"""
        val fallback = decoder.decode(row(malformed))!!
        assertEquals(malformed, fallback.content)
        assertEquals(listOf(AgentContentPart.Text("prefix"), AgentContentPart.Text(malformed)), fallback.contentParts)
    }
}
