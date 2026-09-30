package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source guards: ChatViewModel requires Android/Room; these JVM tests have no Robolectric. */
class LastUsedThinkingLevelTest {
    private val chat = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
    private val repository = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()

    private fun body(signature: String): String {
        val start = chat.indexOf(signature)
        assertTrue("Missing $signature", start >= 0)
        val rest = chat.substring(start + signature.length)
        val end = Regex("""\n    (?:private |internal )?(?:suspend )?fun """).find(rest)?.range?.first
            ?: rest.length
        return rest.substring(0, end).lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `new chats prefer configured default then last choice then off`() {
        val load = body("private fun loadSession()")
        val draft = load.substringAfter("if (isDraft) {").substringBefore("return@launch")
        assertTrue(
            Regex("""_thinkingLevel\.value\s*=\s*config\.defaultThinkingLevel\s*\?:\s*providerRepository\.lastUsedThinkingLevel\s*\?:\s*ThinkingLevel\.OFF""")
                .containsMatchIn(draft),
        )
        assertTrue(draft.contains("applyNewChatDefaultModel()"))
        assertTrue("Opening a draft must not change the remembered choice", !draft.contains("lastUsedThinkingLevel ="))
    }

    @Test
    fun `existing sessions never inherit another chats remembered choice`() {
        val existing = body("private fun loadSession()")
            .substringAfter("val session = chatRepository.getSession(sessionId)")
        assertTrue(existing.contains("_thinkingLevel.value = persistedThinking ?: ThinkingLevel.OFF"))
        assertTrue(!existing.contains("lastUsedThinkingLevel"))
    }

    @Test
    fun `draft choices are remembered before the missing row guard`() {
        val persist = body("private fun persistThinkingOverride(level: ThinkingLevel)")
        val remember = persist.indexOf("providerRepository.lastUsedThinkingLevel = level")
        val draftGuard = persist.indexOf("if (sid.isEmpty()) return")
        assertTrue(remember >= 0 && draftGuard > remember)
        assertTrue(!persist.contains("ensureSession()"))
        assertTrue(persist.contains("updateThinkingOverride(sid, level.name)"))
        assertTrue("OFF is a real choice, not absence", !persist.contains("isEnabled"))
    }

    @Test
    fun `both picker and toggle remember choices even when level is unchanged`() {
        val picker = body("fun setThinkingLevel(level: ThinkingLevel)")
        assertTrue(picker.contains("persistThinkingOverride(clamped)"))
        assertTrue(!picker.contains("if (_thinkingLevel.value == clamped) return"))
        val toggle = body("private fun toggleThinking()")
        assertTrue(toggle.contains("ThinkingLevel.OFF"))
        assertTrue(toggle.contains("persistThinkingOverride(newLevel)"))
    }

    @Test
    fun `remembered level uses persistent preferences and tolerates unknown values`() {
        val property = repository.substringAfter("var lastUsedThinkingLevel: ThinkingLevel?")
            .substringBefore("fun lastUsedVisibleEntry()")
        assertTrue(property.contains("prefs.getString(KEY_LAST_USED_THINKING_LEVEL, null)"))
        assertTrue(property.contains("ThinkingLevel.parseOrNull(it)"))
        assertTrue(property.contains("putString(KEY_LAST_USED_THINKING_LEVEL, value.name)"))
        assertTrue(property.contains("remove(KEY_LAST_USED_THINKING_LEVEL)"))
        assertTrue(property.contains("}.apply()"))
    }
}
