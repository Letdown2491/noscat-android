package io.github.letdown2491.noscat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * User-facing notifications: chat messages, files received while the app
 * is in the background, and join requests. Each kind has its own channel
 * so it can be silenced on its own in system settings. Without the
 * notification permission (Android 13+, asked at first start) these are
 * skipped; the in-app badges still work.
 */
object Notifier {
    private const val CHAT = "noscat-chat"
    private const val FILES = "noscat-files"
    private const val JOINS = "noscat-joins"

    /** Intent extras: what to open when a notification is tapped. */
    const val EXTRA_CHAT = "open_chat"
    const val EXTRA_TAB = "open_tab"

    private fun manager(ctx: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val m = ctx.getSystemService(NotificationManager::class.java) ?: return null
        m.createNotificationChannel(NotificationChannel(CHAT, "Messages", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(NotificationChannel(JOINS, "Join requests", NotificationManager.IMPORTANCE_HIGH))
        return m
    }

    private fun open(ctx: Context, code: Int, fill: Intent.() -> Unit): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            code,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                fill()
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** One notification per conversation, showing the latest message. */
    fun chat(ctx: Context, peer: String, name: String, text: String) {
        val m = manager(ctx) ?: return
        val id = peer.hashCode()
        m.notify(
            "chat",
            id,
            Notification.Builder(ctx, CHAT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(name)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(ctx, id) { putExtra(EXTRA_CHAT, peer) })
                .build(),
        )
    }

    /** The conversation is on screen: its notification goes. */
    fun cancelChat(ctx: Context?, peer: String) {
        ctx?.getSystemService(NotificationManager::class.java)?.cancel("chat", peer.hashCode())
    }

    fun file(ctx: Context, title: String, text: String) {
        val m = manager(ctx) ?: return
        m.notify(
            "file",
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            Notification.Builder(ctx, FILES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open(ctx, 1) { putExtra(EXTRA_TAB, "files") })
                .build(),
        )
    }

    fun join(ctx: Context, peer: String, name: String?) {
        val m = manager(ctx) ?: return
        val who = name?.takeIf { it.isNotBlank() } ?: "A device"
        m.notify(
            "join",
            peer.hashCode(),
            Notification.Builder(ctx, JOINS)
                .setSmallIcon(android.R.drawable.ic_menu_add)
                .setContentTitle("$who wants to join your mesh")
                .setContentText("Open Noscat to approve or decline")
                .setAutoCancel(true)
                .setContentIntent(open(ctx, 2) { putExtra(EXTRA_TAB, "devices") })
                .build(),
        )
    }

    fun cancelJoin(ctx: Context?, peer: String) {
        ctx?.getSystemService(NotificationManager::class.java)?.cancel("join", peer.hashCode())
    }
}
