package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import java.io.File

/** Filesystem/image preparation for replay. Session and vision facts are captured per decode. */
internal class AndroidHistoryMedia(
    private val context: Context,
    private val fsSessionId: String,
    private val mediaBaseDir: File,
    private val noVisionPlaceholder: String?,
) : HistoryMessageDecoder.Media {
    override fun pastedText(relativePath: String): String? = File(mediaBaseDir, relativePath)
        .takeIf { it.exists() }?.readText(Charsets.UTF_8)

    override fun userImage(relativePath: String, mimeType: String, linuxPath: String?): HistoryMessageDecoder.UserImage? {
        val file = File(mediaBaseDir, relativePath)
        if (!file.exists()) return null
        val original = try { file.readBytes() } catch (_: Exception) { return null }
        // Keep full-size originals on disk; only model input gets the same budget as a fresh send.
        val scaled = ImageBudget.downscaleForModel(file, original)
        return HistoryMessageDecoder.UserImage(
            LLMMessage.ImagePart(scaled?.bytes ?: original, scaled?.mimeType ?: mimeType,
                linuxPath = linuxPath, noVisionPlaceholder = noVisionPlaceholder),
            scaled?.let { ImageBudget.downscaleNote(it, linuxPath) },
        )
    }
}
