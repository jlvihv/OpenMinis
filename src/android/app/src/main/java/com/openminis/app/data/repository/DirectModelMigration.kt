package com.openminis.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.model.*
import com.openminis.app.scheduled.ScheduledTaskStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Converts legacy groups before publishing config. Re-running after an interrupted
 * migration is safe: converted sessions are entry bindings and retain timestamps. */
internal fun migrateToDirectModels(context: Context, config: ProviderConfig, credentialAvailable: (ProviderInstance) -> Boolean): Boolean {
    if (config.modelGroups.isEmpty()) return false
    val prefs = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)
    val migrator = LegacyModelBindingMigrator(config, credentialAvailable)
    val originalConfig = kotlinx.serialization.json.Json.encodeToString(ProviderConfig.serializer(), config)
    val db = AppDatabase.getInstance(context)
    runBlocking {
        db.withTransaction {
            val dao = db.chatDao()
            val sessions = dao.listSessions()
            val backup = JSONObject().put("providerConfig", JSONObject(originalConfig))
                .put("sessions", org.json.JSONArray().apply {
                    sessions.forEach { put(JSONObject().put("id", it.id).put("modelBinding", it.modelBinding)
                        .put("modelId", it.modelId).put("thinkingOverride", it.thinkingOverride).put("updatedAt", it.updatedAt)) }
                }).put("tasks", org.json.JSONArray().apply {
                    ScheduledTaskStore(context).all().forEach { put(it.toJson()) }
                }).put("voiceInput", prefs.getString("voice.input.overrideEntryId", null))
                .put("voiceOutput", prefs.getString("voice.output.overrideEntryId", null))
            val backupDir = java.io.File(context.filesDir, "model-migration-backups").apply { mkdirs() }
            val snapshot = java.io.File(backupDir, "${System.currentTimeMillis()}.json")
            snapshot.writeText(backup.toString())
            for (session in sessions) {
                val (binding, group) = migrator.convert(session.modelBinding, session.modelId) ?: continue
                val id = JSONObject(binding).getString("entryId")
                val model = config.modelEntries.firstOrNull { it.id == id }?.model?.id ?: session.modelId
                dao.updateSessionBinding(session.id, binding, model, session.updatedAt)
                if (session.thinkingOverride == null) group.defaultThinkingLevel?.let {
                    dao.updateThinkingOverride(session.id, it.name, session.updatedAt)
                }
            }
        }
    }
    val tasks = ScheduledTaskStore(context)
    for (task in tasks.all()) {
        val (binding, _) = migrator.convert(task.modelBinding, task.modelId) ?: continue
        tasks.upsert(task.copy(modelBinding = binding))
    }
    if (config.defaultModelEntryId == null) config.defaultModelEntryId = migrator.choose(config.defaultPrimaryGroupId)
    val defaultGroup = config.modelGroups.firstOrNull { it.id == config.defaultPrimaryGroupId }
    if (config.defaultThinkingLevel == null) config.defaultThinkingLevel = defaultGroup?.defaultThinkingLevel
    if (config.defaultContextLimitTokens == null) config.defaultContextLimitTokens = defaultGroup?.contextLimitTokens
    if (config.titleModelEntryId == null) config.titleModelEntryId = migrator.choose(config.defaultSubGroupId, allowSystem = false, accepts = { it.model.outputModalities?.contains("text") != false })
    if (config.visionModelEntryId == null) config.visionModelEntryId = migrator.choose(config.visionGroupId, allowSystem = false, accepts = { it.model.hasImageInput })
    val editor = prefs.edit()
    if (prefs.getString("voice.input.overrideEntryId", null) == null) {
        migrator.choose(config.voiceInputGroupId, accepts = { it.model.hasAudioInput })?.let { editor.putString("voice.input.overrideEntryId", it) }
    }
    if (prefs.getString("voice.output.overrideEntryId", null) == null) {
        migrator.choose(config.voiceOutputGroupId, accepts = { it.model.hasAudioOutput })?.let { editor.putString("voice.output.overrideEntryId", it) }
    }
    // Keep original data solely as a recovery snapshot, never as live routing.
    editor.putString("legacyModelGroupsSnapshot", originalConfig)
    check(editor.commit()) { "Could not persist direct-model migration" }
    config.agentLoopGroupIds.flatMap { gid -> config.modelGroups.firstOrNull { it.id == gid }?.memberEntryIds.orEmpty() }
        .filter { id -> config.modelEntries.any { it.id == id } }
        .forEach { if (it !in config.agentLoopModelEntryIds) config.agentLoopModelEntryIds.add(it) }
    config.modelGroups.clear()
    config.agentLoopGroupIds.clear()
    config.defaultPrimaryGroupId = null
    config.defaultSubGroupId = null
    config.voiceInputGroupId = null
    config.voiceOutputGroupId = null
    config.visionGroupId = null
    return true
}
