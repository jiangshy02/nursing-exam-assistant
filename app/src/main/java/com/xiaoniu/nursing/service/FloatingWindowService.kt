package com.xiaoniu.nursing.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.xiaoniu.nursing.ui.FloatingWindowManager

/**
 * 悬浮窗前台服务 — 维持悬浮窗生命周期
 */
class FloatingWindowService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "float_channel"
    }

    private lateinit var floating: FloatingWindowManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
        floating = FloatingWindowManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        floating.show()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        floating.destroy()
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("护理刷题助手")
        .setContentText("悬浮窗运行中 · 点击展开菜单")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                description = "悬浮窗前台服务"
                setShowBadge(false)
            }.let { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }
}
