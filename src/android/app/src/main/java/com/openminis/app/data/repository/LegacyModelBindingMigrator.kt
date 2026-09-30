package com.openminis.app.data.repository

import com.openminis.app.data.model.*
import org.json.JSONObject

internal class LegacyModelBindingMigrator(private val config: ProviderConfig, private val credentialAvailable: (ProviderInstance) -> Boolean = { true }) {
    fun choose(groupId: String?, preferred: String? = null, modelId: String? = null, allowSystem: Boolean = true, accepts: (ModelEntry) -> Boolean = { true }): String? {
        val group = config.modelGroups.firstOrNull { it.id == groupId } ?: return null
        val members = group.memberEntryIds.filter { id ->
            (allowSystem && id.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) || config.modelEntries.any { it.id == id && accepts(it) }
        }
        return preferred?.takeIf { it in members }
            ?: members.firstOrNull { id -> config.modelEntries.any { it.id == id && it.model.id == modelId } }
            ?: members.firstOrNull { id ->
                id.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) || config.modelEntries.any { e ->
                    e.id == id && !e.isHidden && config.instances.any { it.id == e.providerInstanceId && it.isEnabled && credentialAvailable(it) }
                }
            } ?: members.firstOrNull()
    }
    fun convert(binding: String?, modelId: String?): Pair<String, ModelGroup>? {
        val obj = binding?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (obj.optString("type") != "group") return null
        val group = config.modelGroups.firstOrNull { it.id == obj.optString("groupId") } ?: return null
        val preferred = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
        // A group may have been edited since this conversation was used.
        // Preserve the session's concrete model even if it left the group.
        val entryId = preferred?.takeIf { id -> config.modelEntries.any { it.id == id } }
            ?: config.modelEntries.firstOrNull { it.model.id == modelId }?.id
            ?: choose(group.id, preferred, modelId)
            ?: return null
        val direct = JSONObject().put("type", "entry").put("entryId", entryId)
        group.contextLimitTokens?.takeIf { it > 0 }?.let { direct.put("contextLimitTokens", it) }
        return direct.toString() to group
    }
}
