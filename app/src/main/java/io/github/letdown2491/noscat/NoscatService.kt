package io.github.letdown2491.noscat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * Foreground service that owns the client's lifetime while the app is not on
 * screen: without it Android kills the UDP sockets as soon as the app is
 * backgrounded and every tunnel drops. Started once from the activity and
 * left running (START_STICKY) for the life of the process.
 */
class NoscatService : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        ClientHolder.init(this)
        thread(name = "noscat-service-start") {
            ClientHolder.startFromPrefs(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "mesh connection",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("noscat")
            .setContentText("keeping your mesh connection alive")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "noscat-service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NoscatService::class.java))
        }
    }
}
