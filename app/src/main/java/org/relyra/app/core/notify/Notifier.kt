package org.relyra.app.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.relyra.app.MainActivity
import org.relyra.app.R
import org.relyra.app.core.storage.AppSettings

/** What the screen on top is showing, so a message for the chat you are reading does not also ring. */
object Foreground {
    @Volatile var visible: Boolean = false
    @Volatile var openChat: String? = null
}

/** Android notifications for incoming messages and the "kept connected" service. Nothing here leaves the device. */
class Notifier(context: Context, private val settings: AppSettings) {
    private val ctx = context.applicationContext

    init { createChannels() }

    private fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, ctx.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.notif_channel_messages_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, ctx.getString(R.string.call_notification_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.call_notification_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            }
        )
    }

    /** True when Android will actually show our notifications (permission granted, not switched off in system settings). */
    fun systemAllows(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun openIntent(key: String, group: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            putExtra(EXTRA_CHAT, key)
            putExtra(EXTRA_IS_GROUP, group)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** [text] is null when the sender's words must not appear (no body for attachments or when previews are off). */
    fun postMessage(key: String, group: Boolean, title: String, text: String?) {
        if (!settings.notifications.value || !systemAllows()) return
        if (Foreground.visible && Foreground.openChat == key) return
        val generic = ctx.getString(if (group) R.string.notif_new_group_message else R.string.notif_new_message)
        val shown = if (settings.notifPreview.value && text != null) text else generic
        val publicVersion = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relyra)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(generic)
            .build()
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_relyra)
            .setContentTitle(title)
            .setContentText(shown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(key, group))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the post. Nothing else to do.
        }
    }


    fun postCall(peerId: String, title: String) {
        if (!settings.notifications.value || !systemAllows() || (Foreground.visible && Foreground.openChat == peerId)) return
        val text = ctx.getString(R.string.call_incoming_notification)
        val notification = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_relyra)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent(peerId, false))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(callNotificationId(peerId), notification) }
    }

    fun cancelCall(peerId: String) {
        NotificationManagerCompat.from(ctx).cancel(callNotificationId(peerId))
    }

    private fun callNotificationId(peerId: String) = peerId.hashCode() xor 0x43414C4C

    fun cancel(key: String) {
        NotificationManagerCompat.from(ctx).cancel(key.hashCode())
    }

    /** The permanent notification of the foreground service. [connected] is the number of relays that are connected now. */
    fun serviceNotification(connected: Int, enabled: Int): Notification {
        val text = when {
            enabled == 0 -> ctx.getString(R.string.notif_service_none)
            else -> ctx.getString(R.string.notif_service_text, connected, enabled)
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_relyra)
            .setContentTitle(ctx.getString(R.string.notif_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CH_MESSAGES = "relyra_messages"
        const val CH_CALLS = "relyra_calls"
        const val CH_SERVICE = "relyra_service"
        const val ACTION_OPEN_CHAT = "org.relyra.app.OPEN_CHAT"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_IS_GROUP = "is_group"
        const val SERVICE_ID = 4747
    }
}
