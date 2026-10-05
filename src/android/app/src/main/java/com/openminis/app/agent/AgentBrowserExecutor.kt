package com.openminis.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Captured-owner browser execution, artifact persistence and model-facing results. */
internal class AgentBrowserExecutor(private val context: Context, private val pool: () -> BrowserTabPool) {
    suspend fun execute(argsJson: String, input: AgentToolExecutor.InputContext): ToolExecutionResult {
        val action = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser input", false)
        return try {
            input.checkBranch()
            val boundPool = withContext(Dispatchers.Main) {
                input.checkBranch()
                pool()
            }
            val result = boundPool.execute(action, owner = input.sessionId.ifEmpty { null })
            input.checkBranch()
            withContext(Dispatchers.IO) {
                input.checkBranch()
                val title = JSONObject(argsJson).optString("tool_title", "browser")
                var output = result.text
                var persistentImagePath = result.imageFilePath
                var image: ByteArray? = null
                var linuxImagePath: String? = null
                val raw = result.base64Image?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                if (raw != null) {
                    image = resize(raw, 2000) ?: raw
                    val filename = "screenshot_${System.currentTimeMillis() / 1000}_${java.util.UUID.randomUUID()}.jpg"
                    persist(input.fsSessionId, filename, raw)?.let { host ->
                        persistentImagePath = host
                        linuxImagePath = "/var/minis/browser/$filename"
                        minisUrl(linuxImagePath!!)?.let { output += "\nminis_url: $it" }
                    }
                }
                val fetchData = result.fetchedFileData
                val fetchName = result.fetchedFileName
                if (fetchData != null && fetchName != null) {
                    persist(input.fsSessionId, fetchName, fetchData)
                    minisUrl("/var/minis/browser/$fetchName")?.let { output += "\nminis_url: $it" }
                }
                ToolExecutionResult(output, result.success, imageData = image,
                    imageMimeType = if (image != null) "image/jpeg" else null, toolTitle = title,
                    pageURL = result.pageURL, imageFilePath = persistentImagePath, imageLinuxPath = linuxImagePath)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolExecutionResult("Error: ${failure.message}", false) }
    }

    private fun persist(sessionId: String, filename: String, data: ByteArray): String? {
        if (sessionId.isEmpty()) return null
        return try {
            val directory = File(context.filesDir, "minis-sessions/$sessionId/browser").apply { mkdirs() }
            File(directory, filename).apply { writeBytes(data) }.absolutePath
        } catch (failure: Exception) {
            android.util.Log.w("AgentBrowserExecutor", "persistBrowserArtifact failed: ${failure.message}")
            null
        }
    }

    private fun minisUrl(path: String): String? {
        if (!path.startsWith("/var/minis/")) return null
        val rest = path.removePrefix("/var/minis/")
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val encoded = java.net.URLEncoder.encode(rest.substring(slash + 1), "UTF-8").replace("+", "%20")
        return "minis://${rest.substring(0, slash)}/$encoded"
    }

    private fun resize(data: ByteArray, maxEdge: Int): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        try {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxEdge) return null
            val scale = maxEdge.toFloat() / longest
            val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            try {
                return ByteArrayOutputStream().also { resized.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            } finally { if (resized !== bitmap) resized.recycle() }
        } finally { bitmap.recycle() }
    }
}
