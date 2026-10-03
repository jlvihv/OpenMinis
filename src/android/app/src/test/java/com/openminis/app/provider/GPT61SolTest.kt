package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GPT61SolTest {
    @Test fun clientVersionSupports61() {
        val version = OpenAIProvider.CODEX_CLIENT_VERSION.split('.').map { it.toInt() }
        assertTrue(version[0] > 0 || version[1] > 159 || (version[1] == 159 && version[2] >= 2))
    }

    @Test fun catalogSupportsReasoningAndContext() {
        val model = OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6.1-sol" }
        assertEquals(true, model.supportsReasoning)
        assertEquals(272_000, model.contextWindow)
        assertEquals(ThinkingLevel.MAX, model.catalogMaxThinkingLevel)
    }
}
