package com.openminis.app.ui.navigation

import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import org.junit.Assert.*
import org.junit.Test

class ChatLaunchTargetTest {
    @Test
    fun `existing session and HTML shortcut do not create drafts`() {
        val noDraft = { error("existing session must not mint a draft") }
        assertEquals("existing", initialChatTarget(DeepLinkAction.OpenSession("existing"), noDraft)?.sessionId)
        assertEquals("existing", initialChatTarget(DeepLinkAction.OpenHtmlPreview("existing", "/index.html", "Title"), noDraft)?.sessionId)
    }

    @Test
    fun `all fresh chat entry points share draft selection and targeted action`() {
        val draft = { "__new__test" }
        assertEquals(ChatLaunchTarget("__new__test"), initialChatTarget(DeepLinkAction.NewChat, draft))
        assertEquals(ChatLaunchTarget("__new__test", DeepLinkCoordinator.ChatAction.START_VOICE),
            initialChatTarget(DeepLinkAction.NewVoiceChat, draft))
        assertEquals(ChatLaunchTarget("__new__test", DeepLinkCoordinator.ChatAction.OPEN_CAMERA),
            initialChatTarget(DeepLinkAction.NewCameraChat, draft))
    }

    @Test
    fun `home and settings do not request a chat`() {
        val noDraft = { error("not a chat request") }
        assertNull(initialChatTarget(null, noDraft))
        assertNull(initialChatTarget(DeepLinkAction.Unknown, noDraft))
        assertNull(initialChatTarget(DeepLinkAction.OpenSettingsScreen("settings"), noDraft))
    }

    @Test
    fun `assistant action is bound to its intended session and consumed once`() {
        DeepLinkCoordinator.consumePendingChatAction()
        try {
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE, "new-assistant-chat")
            val pending = DeepLinkCoordinator.pendingChatAction.value!!
            assertEquals("new-assistant-chat", pending.sessionId)
            assertNotEquals("outgoing-chat", pending.sessionId)
            assertEquals(DeepLinkCoordinator.ChatAction.START_VOICE, pending.action)
            assertEquals(pending, DeepLinkCoordinator.consumePendingChatAction())
            assertNull(DeepLinkCoordinator.consumePendingChatAction())
        } finally {
            DeepLinkCoordinator.consumePendingChatAction()
        }
    }
}
