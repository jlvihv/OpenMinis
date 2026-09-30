package com.openminis.app.agent.jobs

import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DirectAgentModelSelectionTest {
    private fun entry(provider: String, model: String) = ModelEntry(
        uuid = "$provider/$model", providerInstanceId = provider,
        baseModel = LLMModel(id = model, displayName = model, provider = "openai"),
    )
    private fun config() = ProviderConfig(modelEntries = mutableListOf(
        entry("a", "shared"), entry("b", "shared"), entry("a", "light"),
    ), defaultModelEntryId = "a/shared", subModelEntryId = "a/light")
    private fun pick(pin: String? = null, choice: SubAgentModelChoice = SubAgentModelChoice.SAME_AS_PARENT,
        available: (ModelEntry) -> Boolean = { true }) = selectDirectAgentModel(config(), pin, choice, "b/shared", "shared", available)

    @Test fun inheritsExactProviderWhenModelIdsOverlap() {
        assertEquals("b/shared", pick()!!.entry.id)
        assertEquals(HelperModelOrigin.INHERITED, pick()!!.origin)
    }
    @Test fun pinOverridesDelegatingModelChoice() {
        val r = pick("b/shared", SubAgentModelChoice.SUB_MODEL)!!
        assertEquals("b/shared", r.entry.id)
        assertEquals(HelperModelOrigin.PINNED, r.origin)
    }
    @Test fun unavailablePinFallsBackAndReportsIt() {
        val r = pick("a/light", available = { it.id != "a/light" })!!
        assertEquals("b/shared", r.entry.id)
        assertTrue(r.pinnedModelUnavailable)
    }
    @Test fun defaultAndLightweightUseSeparateDirectSelections() {
        assertEquals("a/shared", pick(choice = SubAgentModelChoice.DEFAULT_MODEL)!!.entry.id)
        assertEquals("a/light", pick(choice = SubAgentModelChoice.SUB_MODEL)!!.entry.id)
    }
    @Test fun unavailableExplicitParentDoesNotSwitchProviderSilently() {
        assertNull(pick(available = { it.id != "b/shared" }))
    }
}
