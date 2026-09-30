package com.openminis.app.data.repository

import com.openminis.app.data.model.*
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.db.toProviderConfig
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectModelMigrationTest {
    private fun entry(instance: String, model: String) = ModelEntry(
        providerInstanceId = instance,
        baseModel = LLMModel(id = model, displayName = model, provider = "test"),
        uuid = "$instance/$model",
    )
    private fun config(): ProviderConfig {
        val a = entry("a", "shared")
        val b = entry("b", "shared")
        return ProviderConfig(
            instances = mutableListOf(
                ProviderInstance("a", "A", ProviderType.openAI, ProviderCredential.apiKey),
                ProviderInstance("b", "B", ProviderType.openAI, ProviderCredential.apiKey),
            ),
            modelEntries = mutableListOf(a, b),
            modelGroups = mutableListOf(ModelGroup(id = "g", name = "old",
                memberEntryIds = mutableListOf(a.id, b.id),
                defaultThinkingLevel = ThinkingLevel.HIGH, contextLimitTokens = 32768)),
        )
    }
    @Test fun preservesProviderIdentityAndSessionDefaults() {
        val result = LegacyModelBindingMigrator(config()).convert(
            """{"type":"group","groupId":"g","lastEntryId":"b/shared"}""", "shared")!!
        val binding = JSONObject(result.first)
        assertEquals("entry", binding.getString("type"))
        assertEquals("b/shared", binding.getString("entryId"))
        assertEquals(32768, binding.getInt("contextLimitTokens"))
        assertEquals(ThinkingLevel.HIGH, result.second.defaultThinkingLevel)
        // Idempotence: a converted binding is never migrated again.
        assertNull(LegacyModelBindingMigrator(config()).convert(result.first, "shared"))
    }
    @Test fun retainsActiveModelWhenOldBindingLacksLastEntry() {
        val c = config()
        val different = entry("b", "active")
        c.modelEntries.add(different)
        c.modelGroups.single().memberEntryIds.add(different.id)
        val result = LegacyModelBindingMigrator(c).convert("""{"type":"group","groupId":"g"}""", "active")!!
        assertEquals(different.id, JSONObject(result.first).getString("entryId"))
    }
    @Test fun defaultSelectionSkipsDisabledProviderAndMissingMember() {
        val c = config()
        c.instances[0].isEnabled = false
        c.modelGroups[0].memberEntryIds.add(0, "missing")
        assertEquals("b/shared", LegacyModelBindingMigrator(c).choose("g"))
        assertNull(LegacyModelBindingMigrator(c).convert("broken JSON", null))
    }
    @Test fun preservesModelThatWasRemovedFromGroup() {
        val c = config()
        val prior = entry("b", "prior")
        c.modelEntries.add(prior)
        val result = LegacyModelBindingMigrator(c).convert("""{"type":"group","groupId":"g"}""", "prior")!!
        assertEquals(prior.id, JSONObject(result.first).getString("entryId"))
    }
    @Test fun preservesExplicitProviderEvenAfterGroupEdit() {
        val c = config()
        c.modelGroups[0].memberEntryIds.remove("b/shared")
        val result = LegacyModelBindingMigrator(c).convert(
            """{"type":"group","groupId":"g","lastEntryId":"b/shared"}""", "shared")!!
        assertEquals("b/shared", JSONObject(result.first).getString("entryId"))
    }
    @Test fun retainsSystemSpeechSelection() {
        val c = config()
        val id = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"
        c.modelGroups[0].memberEntryIds.clear()
        c.modelGroups[0].memberEntryIds.add(id)
        assertEquals(id, LegacyModelBindingMigrator(c).choose("g"))
    }
    @Test fun directDefaultsSurviveDatabaseSnapshotRoundTrip() {
        val c = config().copy(modelGroups = mutableListOf(), defaultModelEntryId = "b/shared",
            titleModelEntryId = "a/shared", visionModelEntryId = "b/shared",
            defaultThinkingLevel = ThinkingLevel.HIGH, defaultContextLimitTokens = 32768)
        val restored = c.toSnapshot(Json).toProviderConfig(Json)
        assertEquals(c.defaultModelEntryId, restored.defaultModelEntryId)
        assertEquals(c.titleModelEntryId, restored.titleModelEntryId)
        assertEquals(c.visionModelEntryId, restored.visionModelEntryId)
        assertEquals(c.defaultThinkingLevel, restored.defaultThinkingLevel)
        assertEquals(c.defaultContextLimitTokens, restored.defaultContextLimitTokens)
        assertTrue(restored.modelGroups.isEmpty())
    }
}
