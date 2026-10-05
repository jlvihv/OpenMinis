package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Auxiliary title requests: admission, candidates, receipts, fallback and compare-and-set settlement. */
internal class AgentTitleRuntime(private val context: Context, private val repository: ChatRepository,
    private val providers: ProviderRepository, private val scope: CoroutineScope) {
    companion object {
        const val SYSTEM_PROMPT = "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text."
        const val UNTITLED = "New Chat"
        fun promptSafe(raw: String, max: Int): String = raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
            .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
            .replace(Regex("\\p{Cf}"), "").replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
            .trim().take(max).trim()
    }
    data class Message(val role: String, val text: String)
    data class Input(val session: String, val title: String, val language: String,
        val messages: List<Message>, val entryId: String?, val modelId: String?, val autoGroup: Boolean,
        val lastResort: LLMProvider?, val lastResortAttribution: ModelAttributionSnapshot?)
    data class Result(val title: String, val category: String?, val folder: String? = null)
    private data class Admission(var attempts: Int = 0, var running: Boolean = false)
    private val admissions = mutableMapOf<String, Admission>()
    private val requests = AgentAuxiliaryRequest(repository)

    fun automatic(input: Input, publish: suspend (Result) -> Unit) {
        if (input.session.isEmpty() || !untitled(input.title) || input.messages.none { it.role == "user" }) return
        val state = synchronized(admissions) {
            val state = admissions.getOrPut(input.session) { Admission() }
            if (state.running || state.attempts >= 3 || !scope.isActive) return
            state.running = true
            state.attempts++
            state
        }
        val worker = scope.launch(Dispatchers.IO) {
            generate(input.session, "auto", input.language, input.autoGroup, input, publish)
        }
        // Also releases admission when cancellation happens before dispatch.
        worker.invokeOnCompletion { synchronized(admissions) { state.running = false } }
    }

    /** Only explicit manual commands call this; no startup sweep or implicit network work. */
    suspend fun regenerate(session: String, language: String): Boolean =
        generate(session, "manual", language, false, null) {}

    private fun untitled(title: String?) = title.isNullOrBlank() || title.trim() == UNTITLED

    private suspend fun generate(id: String, origin: String, language: String, autoGroup: Boolean,
        input: Input?, publish: suspend (Result) -> Unit): Boolean {
        var original: com.openminis.app.data.db.ChatSessionEntity? = null
        var firstUser: String? = input?.messages?.firstOrNull { it.role == "user" }?.text
        var applied = false
        try {
            currentCoroutineContext().ensureActive()
            val session = repository.getSession(id) ?: return false
            original = session
            if (input != null && !untitled(session.title)) return false
            val messages = input?.messages ?: repository.loadMessages(id).mapNotNull { row ->
                if (row.role !in listOf("user", "assistant")) return@mapNotNull null
                val text = text(row.partsJson)
                if (text.isBlank()) null else Message(row.role, text)
            }
            firstUser = messages.firstOrNull { it.role == "user" }?.text ?: return false
            val groups = if (autoGroup) groupContext() else emptyList()
            val prompt = prompt(messages, groups, language, automatic = input != null)
            val primary = input?.entryId?.let { TitleCandidates.PrimarySource.Entry(it) }
                ?: TitleCandidates.PrimarySource.parse(session.modelBinding)
            val candidates = TitleCandidates.forSession(providers, id, primary, input?.modelId ?: session.modelId)
            suspend fun changed(): Boolean {
                val current = repository.getSession(id) ?: return true
                return current.title != session.title || current.category != session.category
            }
            val result = if (candidates.isEmpty() && input?.lastResort != null) {
                currentCoroutineContext().ensureActive()
                if (changed()) null else ask(id, input.lastResort, input.lastResortAttribution, prompt)
            } else TitleCandidates.walk(candidates, origin,
                pauseMs = if (input != null) TitleCandidates.AUTO_PAUSE_MS else 0L,
                shouldStop = { changed() }) { entry ->
                currentCoroutineContext().ensureActive()
                val instance = providers.instance(entry.providerInstanceId) ?: return@walk null
                val provider = TitleCandidates.providerFor(providers, context, entry, id) ?: return@walk null
                val attribution = ModelAttributionSnapshot(provider.model.id, provider.model.displayName,
                    instance.providerType.name, instance.id)
                ask(id, provider, attribution, prompt)
            }
            if (result != null) {
                currentCoroutineContext().ensureActive()
                applied = repository.dao.compareAndSetSessionTitle(id, session.title, session.category,
                    result.title, result.category, System.currentTimeMillis()) > 0
                if (applied) {
                    withContext(Dispatchers.Main) { publish(result) }
                    if (autoGroup && !result.folder.isNullOrEmpty()) {
                        try { assignFolder(id, result.folder) }
                        catch (failure: Exception) {
                            if (failure is CancellationException && TitleCandidates.isRealCancellation(failure)) throw failure
                            AppLogger.warning("TitleGen", "auto-group failed session=${id.take(8)} type=${failure.javaClass.simpleName}")
                        }
                    }
                    AppLogger.info("TitleGen", "outcome=set origin=$origin session=${id.take(8)} titleLen=${result.title.length}")
                }
            } else if (input != null) synchronized(admissions) { admissions[id]?.attempts = 3 }
        } catch (failure: Exception) {
            AppLogger.warning("TitleGen", "outcome=exception origin=$origin session=${id.take(8)} type=${failure.javaClass.simpleName}")
            if (failure is CancellationException && TitleCandidates.isRealCancellation(failure)) throw failure
        } finally {
            // Cancellation may stop the request, but never retargets settlement to the currently open chat.
            if (!applied) withContext(NonCancellable) {
                val session = original ?: repository.getSession(id)
                if (session != null && untitled(session.title)) {
                    val fallback = fallback(firstUser)
                    if (fallback != null) {
                        applied = repository.dao.compareAndSetSessionTitle(id, session.title, session.category,
                            fallback, null, System.currentTimeMillis()) > 0
                        if (applied) withContext(Dispatchers.Main) { publish(Result(fallback, session.category)) }
                    }
                }
            }
        }
        return applied
    }

    private suspend fun ask(id: String, provider: LLMProvider, attribution: ModelAttributionSnapshot?, prompt: String): Result? {
        AppLogger.info("TitleGen", "dispatch session=${id.take(8)} model=${provider.model.id}")
        val text = requests.text(id, provider, attribution, RequestUsageRecord.Purpose.TITLE, prompt, SYSTEM_PROMPT,
            if (provider.model.supportsReasoning == true) 2048 else 100)
        return parse(text).takeIf { it.title.isNotEmpty() }
    }

    private suspend fun groupContext(): List<String> {
        val rendered = repository.listFolders().map { it to promptSafe(it.name, 40) }.filter { it.second.isNotEmpty() }
        val ambiguous = rendered.groupingBy { it.second.lowercase() }.eachCount().filterValues { it > 1 }.keys
        return rendered.filterNot { it.second.lowercase() in ambiguous }.take(30).map { (folder, name) ->
            val description = folder.description?.takeIf { it.isNotBlank() }?.let { promptSafe(it, 100) }?.takeIf { it.isNotEmpty() }
            if (description == null) "\"$name\"" else "\"$name\" — $description"
        }
    }
    private suspend fun assignFolder(id: String, name: String) {
        // Exactly one rendered match: even an exact raw match can be ambiguous after truncation.
        val match = repository.listFolders().filter { promptSafe(it.name, 40).equals(name.trim(), true) }.singleOrNull()
        if (match != null) repository.setFolderIfUnfiled(match.id, id)
    }

    private fun prompt(messages: List<Message>, groups: List<String>, language: String, automatic: Boolean): String {
        val users = messages.filter { it.role == "user" }
        val assistants = messages.filter { it.role == "assistant" && it.text.isNotBlank() }
        return buildString {
            append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
            append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
            if (groups.isNotEmpty()) {
                append("The user organizes chats into groups. Existing groups: [")
                append(groups.joinToString("; "))
                append("]. If this conversation clearly belongs to one of these groups, ")
                append("set \"folder\" to that exact group name. ")
                append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
                append("Never invent a new group name.\n\n")
            }
            append("You MUST respond with valid JSON only. Example:\n")
            append(if (groups.isEmpty()) "{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n"
                else "{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
            if (automatic) append("Conversation:\n")
            append("User: ${users.firstOrNull()?.text.orEmpty().take(200)}\n")
            assistants.firstOrNull()?.text?.take(200)?.takeIf { it.isNotEmpty() }?.let { append("Assistant: $it\n") }
            if (users.size > 1) {
                users.lastOrNull()?.text?.take(200)?.takeIf { it.isNotEmpty() }?.let { append("User: $it\n") }
                assistants.lastOrNull()?.text?.take(200)?.takeIf { it.isNotEmpty() }?.let { append("Assistant: $it\n") }
            }
            append(language)
        }
    }
    private fun text(parts: String): String = runCatching {
        val array = JSONArray(parts)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.takeIf { it.optString("type") == "text" }
            ?.optString("value")?.let { stripAttachments(it) } }.joinToString("\n")
    }.getOrDefault("")
    private fun stripAttachments(raw: String): String {
        val start = raw.indexOf("<user-attached-files>")
        if (start < 0) return raw
        val endTag = "</user-attached-files>"
        val end = raw.indexOf(endTag, start)
        return if (end < 0) raw.substring(0, start) else raw.substring(0, start) + raw.substring(end + endTag.length)
    }
    private fun fallback(raw: String?): String? {
        val cleaned = stripAttachments(raw.orEmpty()).replace(Regex("\\s+"), " ").trim()
        return cleaned.takeIf { it.isNotEmpty() }?.let { if (it.length > 30) it.take(30).trimEnd() + "…" else it }
    }
    private fun parse(raw: String): Result {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching {
            val json = JSONObject(text)
            val title = json.optString("title", "").trim()
            if (title.isNotEmpty()) return Result(title, json.optString("category", "").trim().ifEmpty { null },
                json.optString("folder", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", true) })
        }
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.trim()
        field("title")?.let { return Result(it, field("category"), field("folder")?.takeIf { it != "null" }) }
        return Result(text.lineSequence().firstOrNull().orEmpty().trim().take(50), null)
    }
}
