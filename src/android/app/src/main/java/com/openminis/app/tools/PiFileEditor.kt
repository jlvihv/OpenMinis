package com.openminis.app.tools

import java.text.Normalizer

/** Batch validation against the original file, followed by one write by the caller. */
internal object PiFileEditor {
    data class Edit(val oldText: String, val newText: String)
    data class Result(val content: String, val diff: String, val patch: String, val firstChangedLine: Int)
    private data class Match(val start: Int, val end: Int, val replacement: String)
    private fun lf(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')
    private fun fuzzy(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace(Regex("[‘’‚‛]"), "'").replace(Regex("[“”„‟]"), "\"")
        .replace(Regex("[‐‑‒–—―−]"), "-").replace('\u00a0', ' ')
        .split('\n').joinToString("\n") { it.trimEnd { ch -> ch.isWhitespace() || ch == '\uFEFF' } }
    private fun occurrences(text: String, target: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val index = text.indexOf(target, from)
            if (index < 0) return hits
            hits.add(index); from = index + target.length
        }
    }
    fun apply(raw: String, edits: List<Edit>, path: String): Result {
        require(edits.isNotEmpty()) { "edits must contain at least one replacement" }
        require(edits.all { it.oldText.isNotEmpty() }) { "oldText must not be empty" }
        val bom = if (raw.startsWith('\uFEFF')) "\uFEFF" else ""
        val original = lf(raw.removePrefix(bom))
        val normalized = edits.map { Edit(lf(it.oldText), lf(it.newText)) }
        val useFuzzy = normalized.any { !original.contains(it.oldText) && fuzzy(original).contains(fuzzy(it.oldText)) }
        val base = if (useFuzzy) fuzzy(original) else original
        val matches = normalized.flatMapIndexed { index, edit ->
            val old = if (useFuzzy) fuzzy(edit.oldText) else edit.oldText
            require(old.isNotEmpty()) { "edits[$index].oldText must not normalize to empty text" }
            val hits = occurrences(base, old)
            require(hits.isNotEmpty()) { "Could not find edits[$index] in $path" }
            val fuzzyOld = fuzzy(edit.oldText)
            val duplicateCount = if (fuzzyOld.isEmpty()) hits.size else occurrences(fuzzy(original), fuzzyOld).size
            require(duplicateCount == 1) { "Found $duplicateCount occurrences of edits[$index] in $path; provide unique context" }
            hits.take(1).map { Match(it, it + old.length, edit.newText) }
        }.sortedBy { it.start }
        matches.zipWithNext().forEach { (a, b) -> require(a.end <= b.start) { "Edits overlap in $path; merge them into one edit" } }
        fun replace(source: String, selected: List<Match>, origin: Int = 0): String {
            var value = source
            for (match in selected.asReversed()) value = value.replaceRange(match.start - origin, match.end - origin, match.replacement)
            return value
        }
        val changed = if (!useFuzzy) replace(original, matches) else {
            // Rewrite touched line windows from the normalized base, preserving only
            // untouched windows. Never restore typography over explicitly supplied newText.
            val baseLines = base.split('\n'); val originalLines = original.split('\n')
            fun starts(lines: List<String>): List<Int> { var p = 0; return lines.map { val s = p; p += it.length + 1; s } }
            val baseStarts = starts(baseLines); val originalStarts = starts(originalLines)
            fun lineAt(position: Int) = baseStarts.binarySearch(position).let { if (it >= 0) it else -it - 2 }
            val groups = mutableListOf<Pair<IntRange, MutableList<Match>>>()
            for (match in matches) {
                val span = lineAt(match.start)..lineAt(match.end - 1)
                val last = groups.lastOrNull()
                if (last != null && span.first <= last.first.last) {
                    groups[groups.lastIndex] = (last.first.first..maxOf(last.first.last, span.last)) to last.second.apply { add(match) }
                } else groups.add(span to mutableListOf(match))
            }
            var result = original
            for ((span, selected) in groups.asReversed()) {
                val start = baseStarts[span.first]
                val end = baseStarts.getOrElse(span.last + 1) { base.length }
                val replacement = replace(base.substring(start, end), selected, start)
                result = result.replaceRange(originalStarts[span.first], originalStarts.getOrElse(span.last + 1) { original.length }, replacement)
            }
            result
        }
        require(changed != original) { "No changes made to $path; replacements produced identical content" }
        val crlf = raw.indexOf("\r\n"); val newline = raw.indexOf('\n')
        val ending = if (crlf >= 0 && crlf < newline) "\r\n" else "\n"
        val patch = patch(path, original, changed)
        return Result(bom + changed.replace("\n", ending), patch.second, patch.second, patch.first)
    }
    /** Valid unified patch; a single hunk intentionally favors predictable bounded algorithm cost. */
    private fun patch(path: String, before: String, after: String): Pair<Int, String> {
        fun records(text: String): List<Pair<String, Boolean>> {
            if (text.isEmpty()) return emptyList()
            val lines = text.split('\n')
            return lines.take(if (text.endsWith('\n')) lines.size - 1 else lines.size).mapIndexed { i, line -> line to (i < lines.size - 1) }
        }
        val old = records(before); val new = records(after)
        var prefix = 0
        while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(old.size, new.size) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        val start = (prefix - 4).coerceAtLeast(0)
        val oldEnd = minOf(old.size, old.size - suffix + 4)
        val newEnd = minOf(new.size, new.size - suffix + 4)
        val oldCount = oldEnd - start; val newCount = newEnd - start
        val out = StringBuilder("--- $path\n+++ $path\n@@ -${if (oldCount == 0) start else start + 1},$oldCount +${if (newCount == 0) start else start + 1},$newCount @@\n")
        fun emit(kind: Char, record: Pair<String, Boolean>) {
            out.append(kind).append(record.first).append('\n')
            if (!record.second) out.append("\\ No newline at end of file\n")
        }
        for (i in start until prefix) emit(' ', old[i])
        for (i in prefix until old.size - suffix) emit('-', old[i])
        for (i in prefix until new.size - suffix) emit('+', new[i])
        for (i in old.size - suffix until oldEnd) emit(' ', old[i])
        return prefix + 1 to out.toString()
    }
}
