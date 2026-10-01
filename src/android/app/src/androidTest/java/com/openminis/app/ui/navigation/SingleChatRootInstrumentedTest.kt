package com.openminis.app.ui.navigation

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.deeplink.DeepLinkCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real NavController checks, independent of screen layout and a device's lock state. */
@RunWith(AndroidJUnit4::class)
class SingleChatRootInstrumentedTest {
    private fun controller(restore: android.os.Bundle? = null): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        return NavHostController(context).apply {
            setViewModelStore(ViewModelStore())
            setLifecycleOwner(owner)
            navigatorProvider.addNavigator(ComposeNavigator())
            if (restore != null) restoreState(restore)
            graph = createGraph(startDestination = Routes.SESSION_LIST) {
                composable(Routes.SESSION_LIST) {}
                composable(Routes.SETTINGS) {}
            }
        }
    }

    private fun onMain(test: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(test)
    }

    @Test
    fun repeatedSessionAndAssistantEntriesNeverPushAnotherHost() = onMain {
        val nav = controller()
        val root = nav.currentBackStackEntry!!
        repeat(5) { index ->
            nav.openChat("session-$index")
            assertSame(root, nav.currentBackStackEntry)
            assertNull(nav.previousBackStackEntry)
            assertEquals("session-$index", root.savedStateHandle.get<String>(ChatNavigation.PENDING_SESSION))
        }
        nav.openChat("assistant", DeepLinkCoordinator.ChatAction.START_VOICE)
        assertSame(root, nav.currentBackStackEntry)
        assertNull(nav.previousBackStackEntry)
        assertEquals("assistant", DeepLinkCoordinator.consumePendingChatAction()?.sessionId)
    }

    @Test
    fun openingSessionFromSettingsReturnsToTheExistingRoot() = onMain {
        val nav = controller()
        val root = nav.currentBackStackEntry!!
        nav.navigate(Routes.SETTINGS)
        nav.openChat("chosen-session")
        assertSame(root, nav.currentBackStackEntry)
        assertEquals(Routes.SESSION_LIST, nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry)
    }

    @Test
    fun ordinarySettingsBackPreservesSelectedSession() = onMain {
        val nav = controller()
        val root = nav.currentBackStackEntry!!
        root.savedStateHandle[ChatNavigation.SELECTED_SESSION] = "selected"
        nav.navigate(Routes.SETTINGS)
        assertTrue(nav.popBackStack())
        assertSame(root, nav.currentBackStackEntry)
        assertEquals("selected", root.savedStateHandle.get<String>(ChatNavigation.SELECTED_SESSION))
        assertNull(nav.previousBackStackEntry)
    }

    @Test
    fun rootMailboxAndSelectedSessionSurviveStateRestoration() = onMain {
        val nav = controller()
        nav.currentBackStackEntry!!.savedStateHandle[ChatNavigation.SELECTED_SESSION] = "persisted"
        nav.openChat("requested")
        val restored = controller(nav.saveState())
        val state = restored.currentBackStackEntry!!.savedStateHandle
        assertEquals("persisted", state.get<String>(ChatNavigation.SELECTED_SESSION))
        assertEquals("requested", state.get<String>(ChatNavigation.PENDING_SESSION))
        assertNull(restored.previousBackStackEntry)
    }
}
