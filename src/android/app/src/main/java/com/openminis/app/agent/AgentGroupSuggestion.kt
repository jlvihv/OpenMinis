package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Explicit folder suggestions: only titles/categories/descriptions, never conversation content. */
internal class AgentGroupSuggestion(private val context: Context, private val repository: ChatRepository,
    private val providers: ProviderRepository) {
    sealed interface Result {
        data class Merge(val folderId: String, val folderName: String) : Result
        data class Create(val name: String, val description: String?) : Result
    }
    companion object {
        private const val SYSTEM = "You organize chat sessions into folders. Respond with a single valid JSON object only."
        fun parse(text: String, folders: List<FolderEntity>): Result? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
            val folder = json.optString("folder").takeIf { it.isNotBlank() }
            if (json.optString("decision").lowercase() == "merge" && folder != null) {
                val match = folders.filter { it.name.trim().equals(folder.trim(), true) }.maxByOrNull { it.updatedAt }
                if (match != null) return Result.Merge(match.id, match.name)
            }
            val name = (json.optString("name").takeIf { it.isNotBlank() } ?: folder)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val description = json.optString("description").trim().takeIf { it.isNotEmpty() }?.take(FolderEntity.DESC_MAX_CHARS)
            return Result.Create(name, description)
        }
    }
    suspend fun suggest(sessionIds: List<String>): Result {
        currentCoroutineContext().ensureActive()
        val sorted = sessionIds.distinct().sorted()
        require(sorted.isNotEmpty()) { "No sessions selected" }
        val sessions = sorted.mapNotNull { repository.getSession(it) }
        require(sessions.isNotEmpty()) { "No sessions found" }
        val eligible = providers.allVisibleEntries().filter(TitleCandidates::isTitleEligible)
        val dedicated = providers.resolveTitleSubEntry()?.takeIf { it in eligible }
        val anchor = sessions.firstNotNullOfOrNull { session -> eligible.firstOrNull { it.model.id == session.modelId } }
        val candidates = (listOfNotNull(dedicated, anchor) + eligible).distinctBy { it.id }
        require(candidates.isNotEmpty()) { "No sub model available" }
        // Every request is charged once to this stable selected session, never to a later picker selection.
        val owner = sessions.firstOrNull { it.modelId == anchor?.model?.id }?.id ?: sessions.first().id
        val folders = repository.listFolders()
        val names = mutableSetOf<String>()
        val folderLines = mutableListOf<String>()
        for (folder in folders) {
            if (!names.add(folder.name.lowercase())) continue
            val members = repository.sessionIdsInFolder(folder.id).take(3).mapNotNull { repository.getSession(it)?.title }
            val description = folder.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            folderLines += "- \"${folder.name}\"$description: ${members.joinToString(" / ")}"
        }
        val sessionLines = sessions.take(20).map { "- ${it.title ?: "Untitled"} [${it.category ?: "other"}]" }
        val prompt = buildString {
            append("The user selected these chat sessions to file into a folder:\n")
            append(sessionLines.joinToString("\n"))
            append("\n\n")
            append(if (folderLines.isEmpty()) "The user has no folders yet."
                else "Existing folders with sample member titles:\n${folderLines.joinToString("\n")}")
            append("\n\n")
            append("Decide: merge them into ONE existing group (only if they clearly fit it), ")
            append("or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). ")
            append("For a new group also write \"description\": one sentence (under 100 characters, same language as the name) ")
            append("describing what belongs in it — it will guide future automatic grouping.\n\n")
            append("You MUST respond with valid JSON only. Examples:\n")
            append("{\"decision\": \"merge\", \"folder\": \"Work\"}\n")
            append("{\"decision\": \"create\", \"name\": \"Trip Planning\", \"description\": \"Flights, hotels and itineraries for upcoming trips\"}")
        }
        val requests = AgentAuxiliaryRequest(repository)
        val result = TitleCandidates.walk(candidates, "group-suggest", maxTries = candidates.size,
            shouldStop = { repository.getSession(owner) == null }) { entry ->
            currentCoroutineContext().ensureActive()
            val instance = providers.instance(entry.providerInstanceId) ?: return@walk null
            val provider = TitleCandidates.providerFor(providers, context, entry, owner) ?: return@walk null
            val attribution = ModelAttributionSnapshot(provider.model.id, provider.model.displayName, instance.providerType.name, instance.id)
            val text = requests.text(owner, provider, attribution, RequestUsageRecord.Purpose.GROUP_SUGGEST,
                prompt, SYSTEM, if (entry.model.supportsReasoning == true) 2048 else 256)
            parse(text, folders)
        }
        return result ?: error("No usable group suggestion")
    }
}
