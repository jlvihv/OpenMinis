package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator

/** The single root owns list/detail state; opening a chat never pushes another host. */
internal object ChatNavigation {
    const val PENDING_SESSION = "chat_open_session"
    const val SELECTED_SESSION = "chat_selected_session"
}

internal data class ChatLaunchTarget(
    val sessionId: String,
    val action: DeepLinkCoordinator.ChatAction? = null,
)

internal fun initialChatTarget(
    deepLink: DeepLinkAction?,
    createDraft: () -> String = ::newDraftSessionId,
): ChatLaunchTarget? = when (deepLink) {
    is DeepLinkAction.OpenSession -> ChatLaunchTarget(deepLink.sessionId)
    is DeepLinkAction.OpenHtmlPreview -> ChatLaunchTarget(deepLink.sessionId)
    is DeepLinkAction.NewChat -> ChatLaunchTarget(createDraft())
    is DeepLinkAction.NewVoiceChat -> ChatLaunchTarget(createDraft(), DeepLinkCoordinator.ChatAction.START_VOICE)
    is DeepLinkAction.NewCameraChat -> ChatLaunchTarget(createDraft(), DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
    else -> null
}

/** External requests may arrive during STARTED, so unlike UI taps this is not RESUMED-gated. */
internal fun NavController.openChat(sessionId: String, action: DeepLinkCoordinator.ChatAction? = null) {
    val root = getBackStackEntry(Routes.SESSION_LIST)
    if (action != null) DeepLinkCoordinator.setPendingChatAction(action, sessionId)
    root.savedStateHandle[ChatNavigation.PENDING_SESSION] = sessionId
    if (currentDestination?.route != Routes.SESSION_LIST) {
        popBackStack(Routes.SESSION_LIST, inclusive = false)
    }
}

/** Keep the existing transition-race guard for user-triggered links from other screens. */
internal fun NavController.safeOpenChat(sessionId: String) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    openChat(sessionId)
}
