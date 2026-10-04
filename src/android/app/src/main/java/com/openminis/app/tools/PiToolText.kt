package com.openminis.app.tools

/** Pi's model-facing limits count UTF-8 bytes, not UTF-16 characters. */
internal object PiToolText {
    const val MAX_LINES = 2000
    const val MAX_BYTES = 50 * 1024
    data class Truncated(val content: String, val truncated: Boolean, val outputLines: Int,
        val totalLines: Int, val firstLineExceedsLimit: Boolean = false, val partial: Boolean = false)
    fun utf8Prefix(text: String, budget: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= budget) return text
        var end = budget
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
    fun utf8Suffix(text: String, budget: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= budget) return text
        var start = bytes.size - budget
        while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
    fun head(text: String, maxLines: Int = MAX_LINES, maxBytes: Int = MAX_BYTES): Truncated {
        val lines = text.split('\n')
        if (lines.size <= maxLines && text.toByteArray(Charsets.UTF_8).size <= maxBytes)
            return Truncated(text, false, lines.size, lines.size)
        if (lines.first().toByteArray(Charsets.UTF_8).size > maxBytes)
            return Truncated("", true, 0, lines.size, firstLineExceedsLimit = true)
        val result = mutableListOf<String>()
        var used = 0
        for (line in lines.take(maxLines)) {
            val size = line.toByteArray(Charsets.UTF_8).size + if (result.isEmpty()) 0 else 1
            if (used + size > maxBytes) break
            result.add(line); used += size
        }
        return Truncated(result.joinToString("\n"), true, result.size, lines.size)
    }
    fun tail(text: String): Truncated {
        val lines = text.split('\n')
        if (lines.size <= MAX_LINES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            return Truncated(text, false, lines.size, lines.size)
        val result = mutableListOf<String>()
        var used = 0
        for (line in lines.takeLast(MAX_LINES).asReversed()) {
            val bytes = line.toByteArray(Charsets.UTF_8)
            val size = bytes.size + if (result.isEmpty()) 0 else 1
            if (used + size > MAX_BYTES) {
                if (result.isEmpty()) {
                    var start = bytes.size - MAX_BYTES
                    while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
                    return Truncated(String(bytes, start, bytes.size - start, Charsets.UTF_8), true, 1, lines.size, partial = true)
                }
                break
            }
            result.add(line); used += size
        }
        return Truncated(result.asReversed().joinToString("\n"), true, result.size, lines.size)
    }
    fun read(text: String, path: String, offset: Int = 1, limit: Int? = null): String {
        val lines = text.split('\n')
        val start = (offset - 1).coerceAtLeast(0)
        require(start < lines.size) { "Offset $offset is beyond end of file (${lines.size} lines total)" }
        require(limit == null || limit >= 0) { "limit must not be negative" }
        val selected = lines.drop(start).take(limit ?: Int.MAX_VALUE).joinToString("\n")
        val t = head(selected)
        if (t.firstLineExceedsLimit) {
            val size = String.format(java.util.Locale.ROOT, "%.1fKB", lines[start].toByteArray(Charsets.UTF_8).size / 1024.0)
            val quoted = "'" + path.replace("'", "'\\''") + "'"
            return "[Line ${start + 1} is $size, exceeds 50.0KB limit. Use bash: sed -n '${start + 1}p' $quoted | head -c $MAX_BYTES]"
        }
        val remaining = start + (limit?.coerceAtMost(lines.size - start) ?: t.outputLines)
        return when {
            t.truncated -> t.content + "\n\n[Showing lines ${start + 1}-${start + t.outputLines} of ${lines.size}" +
                (if (t.outputLines >= MAX_LINES) "" else " (50.0KB limit)") + ". Use offset=${start + t.outputLines + 1} to continue.]"
            remaining < lines.size -> t.content + "\n\n[${lines.size - remaining} more lines in file. Use offset=${remaining + 1} to continue.]"
            else -> t.content
        }
    }
    fun imageMime(file: java.io.File): String? {
        if (!file.isFile) return null
        val bytes = file.inputStream().use { input -> ByteArray(12).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        return when {
            bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) -> "image/png"
            bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            bytes.size >= 2 && bytes[0] == 66.toByte() && bytes[1] == 77.toByte() -> "image/bmp"
            else -> null
        }
    }
}
