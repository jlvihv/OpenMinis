package com.openminis.app.ui.navigation

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Structural guard: duplicate list hosts must be impossible, not cleaned up on Back. */
class ChatReturnToListTest {
    @Test
    fun `the outer graph has exactly one chat and list host`() {
        val graph = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(graph.contains("startDestination = Routes.SESSION_LIST"))
        assertEquals(1, Regex("ChatSplitScaffoldRoute\\(").findAll(graph).count())
        assertFalse(graph.contains("const val CHAT ="))
        assertFalse(graph.contains("fun chat(sessionId:"))
    }

    @Test
    fun `external chat requests reuse the root rather than push destinations`() {
        val source = ProductionSources.read("ui/navigation/ChatNavigation.kt")
        val open = source.substringAfter("internal fun NavController.openChat(")
            .substringBefore("/** Keep the existing")
        assertTrue(open.contains("getBackStackEntry(Routes.SESSION_LIST)"))
        assertTrue(open.contains("root.savedStateHandle[ChatNavigation.PENDING_SESSION] = sessionId"))
        assertTrue(open.contains("popBackStack(Routes.SESSION_LIST, inclusive = false)"))
        assertFalse(open.contains("navigate("))
    }

    @Test
    fun `back stays in the pane navigator without outer cleanup patches`() {
        val source = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
        assertTrue(source.contains("currentSessionChanged(persistedSessionId)"))
        assertTrue(source.contains("LaunchedEffect(requestedSessionId, navigator)"))
        assertTrue(source.contains("currentRequestHandled(requested)"))
        assertFalse(source.contains("onReturnToList"))
        assertFalse(source.contains("popUpTo("))
    }

    @Test
    fun `session observation stays in a stable composition slot before scaffold animations`() {
        val source = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
        assertTrue(source.contains(".rememberPersistedId(currentSessionId.orEmpty())"))
        assertFalse(source.contains("currentSessionId?.let {"))
    }

    @Test
    fun `phone exit retains the conversation and never renders tablet placeholder`() {
        val source = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
        assertTrue(source.contains("currentSessionId ?: lastDetailSessionId.takeUnless { twoPane }"))
        assertTrue(source.contains("if (twoPane) NoConversationSelected("))
    }

    @Test
    fun `activity tracks pane selection and does not replay launch intents on recreation`() {
        val source = ProductionSources.read("MainActivity.kt")
        assertTrue(source.contains("ChatNavigation.SELECTED_SESSION"))
        assertTrue(source.contains("savedInstanceState != null -> DeepLinkAction.Unknown"))
        assertFalse(source.contains("Routes.CHAT"))
        assertFalse(source.contains("Routes.chat("))
    }
}
