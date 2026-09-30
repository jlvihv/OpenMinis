package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StatusNotificationLayoutTest {
    private val source get() = File("src/main/java/com/openminis/app/service/AgentForegroundService.kt").readText()

    @Test fun `agent notification contains no progress or stop action`() {
        val builders = source.substringAfter("private fun buildNotification(")
        assertTrue(builders.contains("Notification.BigTextStyle()"))
        assertFalse(builders.contains("Notification.ProgressStyle()"))
        assertFalse(builders.contains(".setProgress("))
        assertFalse(builders.contains(".addAction("))
        assertFalse(builders.contains("stopPendingIntent"))
    }

    @Test fun `conversation title is first and status second without a run timer`() {
        assertTrue(source.contains("val titleText = sessionLabel"))
        assertTrue(source.contains("val collapsedText = statusText"))
        assertTrue(source.contains("val shortCritical = chipContentText(statusText)"))
        assertFalse(source.contains(".setUsesChronometer(!isCompleted)"))
        assertFalse(source.contains("val timeString = chipTimerText(elapsedMs)"))
        val strings = File("src/main/res/values-zh/strings.xml").readText()
        assertTrue(strings.contains("name=\"notif_task_completed_body\">任务已完成。</string>"))
        assertFalse(strings.contains("点击打开聊天查看回复。"))
    }

    @Test fun `idle presence removes the foreground notification without cancelling work`() {
        val cleanup = source.substringAfter("private fun removeIdleStatusNotification()")
            .substringBefore("private fun refreshOngoingNotification()")
        assertTrue(cleanup.contains("if (SessionActivityTracker.activeSessions.value.isNotEmpty()) return"))
        assertTrue(cleanup.contains("hideStatusNotification()"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(cleanup.contains("releaseWakeLock()"))
        assertFalse(cleanup.contains("cancelAllActiveStreams"))
        val start = source.substringAfter("override fun onStartCommand(").substringBefore("override fun onBind(")
        assertTrue(start.contains("removeIdleStatusNotification()"))
        assertTrue(start.contains("acquireWakeLock()"))
        val observer = source.substringAfter("private fun applyOverlayState(").substringBefore("MUTUAL EXCLUSION")
        assertTrue(observer.contains("refreshOngoingNotification()"))
    }

    @Test fun `viewing active chat hides status but background work still shows it`() {
        assertFalse(shouldShowAgentStatus(setOf("a"), setOf("a"), true))
        assertTrue(shouldShowAgentStatus(setOf("a"), setOf("a"), false))
        assertTrue(shouldShowAgentStatus(setOf("a"), emptySet(), true))
        assertTrue(shouldShowAgentStatus(setOf("a", "b"), setOf("a"), true))
        assertFalse(shouldShowAgentStatus(emptySet(), setOf("a"), false))
        val refresh = source.substringAfter("private fun refreshOngoingNotification()")
            .substringBefore("private fun acquireWakeLock()")
        assertTrue(refresh.contains("if (!shouldDisplayStatusNotification())"))
        assertTrue(refresh.contains("if (!statusForegroundAttached)"))
        assertTrue(refresh.contains("startForeground("))
    }

    @Test fun `completion notifications remain dismissible and suppressed in foreground`() {
        val notifier = File("src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt").readText()
        assertTrue(notifier.contains("if (isAppForeground()) return"))
        assertTrue(notifier.contains(".setAutoCancel(true)"))
        assertFalse(notifier.contains(".setOngoing(true)"))
    }
}
