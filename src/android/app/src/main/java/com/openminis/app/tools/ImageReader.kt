package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.ensureActive
import com.openminis.app.data.model.ModelImageResizeOptions
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Native Android image processing for read, using Pi's inline-image policy. */
object ImageReader {
    const val NON_VISION_NOTE = "[Current model does not support images. The image will be omitted from this request.]"

    fun pathNote(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
        "[The image shown above is also saved at $it — this is that same image, not an additional one. " +
            "Use the path for file operations such as cropping or inspecting metadata.]"
    }

    private data class Processed(val bytes: ByteArray, val mime: String, val hints: List<String>)

    suspend fun execute(argsJson: String, sessionId: String, context: Context,
        options: ModelImageResizeOptions? = null, supportsImages: Boolean = true): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = CoreToolNames.linuxPath(args.getString("path"))
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context) ?: error("Cannot resolve path: $path")
            require(file.isFile && file.canRead()) { "Not a readable file: $path" }
            val sourceMime = PiToolText.imageMime(file) ?: error("Not a supported image: $path")
            val image = process(file, sourceMime, options)
            val note = if (image == null) "Read image file [$sourceMime]\n[Image omitted: could not be resized below the inline image size limit.]"
                else (listOf("Read image file [${image.mime}]") + image.hints).joinToString("\n")
            ToolExecutionResult(
                output = note + if (!supportsImages) "\n$NON_VISION_NOTE" else "",
                success = true,
                imageData = image?.bytes,
                imageMimeType = image?.mime,
                imageLinuxPath = if (image != null) path else null,
                imageFilePath = if (image != null) file.absolutePath else null,
                toolTitle = args.optString("tool_title", ReadTool.NAME),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { ToolExecutionResult("Error reading image: ${e.message}", false) }
    }

    private suspend fun process(file: File, sourceMime: String, options: ModelImageResizeOptions?): Processed? {
        val coroutine = kotlinx.coroutines.currentCoroutineContext()
        val maxWidth = options?.maxWidth ?: 2000
        val maxHeight = options?.maxHeight ?: 2000
        val maxBytes = options?.maxBytes ?: 4_718_592 // 4.5 MiB of base64
        val quality = options?.jpegQuality ?: 80
        if (maxWidth <= 0 || maxHeight <= 0 || maxBytes <= 0 || quality !in 0..100) return null
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transposed = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
            val width = if (transposed) bounds.outHeight else bounds.outWidth
            val height = if (transposed) bounds.outWidth else bounds.outHeight
            fun fits(size: Long) = ((size + 2) / 3) * 4 < maxBytes
            if (sourceMime in setOf("image/png", "image/jpeg", "image/gif", "image/webp") &&
                width <= maxWidth && height <= maxHeight && fits(file.length())) {
                val bytes = file.readBytes()
                if (fits(bytes.size.toLong())) return Processed(bytes, sourceMime, emptyList())
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 4000) decode.inSampleSize *= 2
            val raw = BitmapFactory.decodeFile(file.absolutePath, decode)?.also(bitmaps::add) ?: return null
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            val oriented = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                .also { if (it !== raw) bitmaps.add(it) }
            var targetWidth = width
            var targetHeight = height
            if (targetWidth > maxWidth) { targetHeight = (targetHeight.toDouble() * maxWidth / targetWidth).roundToInt().coerceAtLeast(1); targetWidth = maxWidth }
            if (targetHeight > maxHeight) { targetWidth = (targetWidth.toDouble() * maxHeight / targetHeight).roundToInt().coerceAtLeast(1); targetHeight = maxHeight }
            // Sampling bounds Android heap usage; never upscale a sampled decode.
            val scale = minOf(1.0, oriented.width.toDouble() / targetWidth, oriented.height.toDouble() / targetHeight)
            targetWidth = (targetWidth * scale).roundToInt().coerceAtLeast(1)
            targetHeight = (targetHeight * scale).roundToInt().coerceAtLeast(1)
            while (true) {
                coroutine.ensureActive()
                val resized = Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
                try {
                    val formats = listOf(Bitmap.CompressFormat.PNG to 100) + listOf(quality, 85, 70, 55, 40).distinct().map { Bitmap.CompressFormat.JPEG to it }
                    for ((format, q) in formats) {
                        coroutine.ensureActive()
                        val bytes = ByteArrayOutputStream().use { out ->
                            if (!resized.compress(format, q, out)) return null
                            out.toByteArray()
                        }
                        if (fits(bytes.size.toLong())) {
                            val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
                            val hints = mutableListOf<String>()
                            if (sourceMime == "image/bmp") hints.add("[Image converted from $sourceMime to $mime.]")
                            if (!(sourceMime == "image/bmp" && mime == "image/png" && targetWidth == width && targetHeight == height)) {
                                hints.add("[Image: original ${width}x${height}, displayed at ${targetWidth}x${targetHeight}. Multiply coordinates by " +
                                    String.format(Locale.ROOT, "%.2f", width.toDouble() / targetWidth) + " to map to original image.]")
                            }
                            return Processed(bytes, mime, hints)
                        }
                    }
                } finally { if (resized !== oriented) resized.recycle() }
                if (targetWidth == 1 && targetHeight == 1) return null
                targetWidth = (targetWidth * 0.75).toInt().coerceAtLeast(1)
                targetHeight = (targetHeight * 0.75).toInt().coerceAtLeast(1)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        catch (_: OutOfMemoryError) { return null }
        finally { bitmaps.asReversed().forEach { if (!it.isRecycled) it.recycle() } }
    }
}
