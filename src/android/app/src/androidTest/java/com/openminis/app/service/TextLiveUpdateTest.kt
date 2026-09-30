package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextLiveUpdateTest {
    @Test fun textOnlyNotificationSupportsLiveUpdatesWithoutProgressOrActions() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "live_update_text_test"
        manager.createNotificationChannel(NotificationChannel(channelId, "Live Update test", NotificationManager.IMPORTANCE_LOW))
        try {
            val notification = Notification.Builder(context, channelId)
                .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
                .setContentTitle("检查共享文件")
                .setContentText("测试会话")
                .setStyle(Notification.BigTextStyle().bigText("测试会话"))
                .setOngoing(true)
                .setShortCriticalText("检查文件")
                .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                .build()
            assertTrue("Text-only Live Update must remain eligible", notification.hasPromotableCharacteristics())
            assertEquals("android.app.Notification\u0024BigTextStyle", notification.extras.getString("android.template"))
            assertFalse(notification.extras.containsKey("android.progress"))
            assertTrue(notification.actions.isNullOrEmpty())
        } finally {
            manager.deleteNotificationChannel(channelId)
        }
    }
}
