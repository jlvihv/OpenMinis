package com.openminis.app.tools

import com.openminis.app.data.EnvVarRedactor
import java.io.File
import java.io.Writer

/** Disk-backed stdout with bounded script head/tail and model tail. Owner-thread only. */
internal class PiBashCapture(private val directory: File) {
    private val prefix = StringBuilder()
    private val tail = StringBuilder()
    private val scriptTail = StringBuilder()
    private var writer: Writer? = null
    private var closed = false
    private var fileFailed = false
    private var highSurrogate = false
    var file: File? = null
        private set
    var bytes = 0L
        private set
    var lines = 1L
        private set
    private var chars = 0L
    val prefixText: String get() = prefix.toString().let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    val tailText: String get() = tail.toString().let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
    val scriptTailText: String get() = scriptTail.toString().let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
    val modelTruncated get() = bytes > PiToolText.MAX_BYTES || lines > PiToolText.MAX_LINES

    fun append(text: String) {
        check(!closed)
        chars += text.length
        for (ch in text) {
            if (highSurrogate) {
                highSurrogate = false
                if (ch.isLowSurrogate()) { bytes += 4; continue }
                bytes += 3
            }
            when {
                ch.isHighSurrogate() -> highSurrogate = true
                ch.code < 0x80 -> bytes++
                ch.code < 0x800 -> bytes += 2
                else -> bytes += 3
            }
            if (ch == '\n') lines++
        }
        val keep = PiBashOutput.SCRIPT_MAX_BYTES + 2
        if (prefix.length < keep) prefix.append(text, 0, minOf(text.length, keep - prefix.length))
        scriptTail.append(text)
        val scriptKeep = PiBashOutput.SCRIPT_MAX_BYTES / 2 + 2
        if (scriptTail.length > scriptKeep) scriptTail.delete(0, scriptTail.length - scriptKeep)
        tail.append(text)
        if (tail.length > PiToolText.MAX_BYTES + 2) tail.delete(0, tail.length - PiToolText.MAX_BYTES - 2)
        if (!fileFailed) try {
            if (writer == null) {
                directory.mkdirs()
                file = File.createTempFile("bash-capture-", ".tmp", directory)
                writer = file!!.bufferedWriter(Charsets.UTF_8)
            }
            writer!!.write(text)
        } catch (_: Exception) { failFile() }
    }
    private fun failFile() {
        fileFailed = true
        runCatching { writer?.close() }; writer = null
        runCatching { file?.delete() }; file = null
    }
    fun finish() {
        if (closed) return
        closed = true
        if (highSurrogate) { bytes += 3; highSurrogate = false }
        try { writer?.close(); writer = null } catch (_: Exception) { failFile() }
    }
    fun discard() { finish(); runCatching { file?.delete() }; file = null }

    /** Same longest-first replacement order as EnvVarRedactor, including cross-chunk matches. */
    fun redact(values: Collection<String>, checkCancellation: () -> Unit = {}): Pair<PiBashCapture, Int> {
        checkCancellation()
        finish()
        val candidates = values.filter { it.length >= EnvVarRedactor.MIN_MATCH_LEN }.distinct().sortedByDescending { it.length }
        if (candidates.isEmpty()) return this to 0
        if (file == null && chars > PiBashOutput.SCRIPT_MAX_BYTES + 2) {
            // Disk failure: never pretend the retained windows are a complete archive.
            val (head, h) = EnvVarRedactor.redact(prefixText, candidates)
            val (end, t) = EnvVarRedactor.redact(tailText, candidates)
            val (scriptEnd, s) = EnvVarRedactor.redact(scriptTailText, candidates)
            prefix.setLength(0); prefix.append(head); tail.setLength(0); tail.append(end)
            scriptTail.setLength(0); scriptTail.append(scriptEnd)
            return this to h + t + s
        }
        val masked = PiBashCapture(directory)
        val emitted = StringBuilder()
        var sink: (String) -> Unit = { text ->
            emitted.append(text)
            if (emitted.length >= 8192) { masked.append(emitted.toString()); emitted.setLength(0) }
        }
        val stages = mutableListOf<ReplaceStage>()
        for (value in candidates.asReversed()) {
            val stage = ReplaceStage(value, EnvVarRedactor.mask(value), sink)
            stages.add(0, stage); sink = stage::append
        }
        try {
            val source = file
            if (source == null) sink(prefixText) else source.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                while (true) { checkCancellation(); val n = reader.read(buffer); if (n < 0) break; sink(String(buffer, 0, n)) }
            }
            stages.forEach { it.finish() }
            if (emitted.isNotEmpty()) masked.append(emitted.toString())
            masked.finish()
            discard()
            return masked to stages.count { it.matched }
        } catch (error: Exception) { masked.discard(); throw error }
    }
    private class ReplaceStage(val target: String, val replacement: String, val sink: (String) -> Unit) {
        val pending = StringBuilder()
        var matched = false
        fun append(text: String) {
            pending.append(text)
            while (true) {
                val safe = pending.length - target.length + 1
                if (safe <= 0) return
                val match = pending.indexOf(target)
                if (match < 0) { sink(pending.substring(0, safe)); pending.delete(0, safe); return }
                if (match > 0) sink(pending.substring(0, match))
                sink(replacement); matched = true
                pending.delete(0, match + target.length)
            }
        }
        fun finish() { if (pending.isNotEmpty()) sink(pending.toString()); pending.setLength(0) }
    }
}
