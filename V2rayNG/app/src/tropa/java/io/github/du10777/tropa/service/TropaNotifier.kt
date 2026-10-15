package io.github.du10777.tropa.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import io.github.du10777.tropa.ui.main.TropaMainActivity

/** Silent notifications: losing and regaining the connection, a new Tropa version (own channels, no sound). */
object TropaNotifier {
    private const val CHANNEL_ID = "tropa_connection"
    private const val NOTIFICATION_ID = 4201
    private const val UPDATE_CHANNEL_ID = "tropa_updates"
    private const val UPDATE_NOTIFICATION_ID = 4202
    private const val RESTORED_TIMEOUT_MS = 10_000L

    fun notifyLost(context: Context) =
        post(context, CHANNEL_ID, NOTIFICATION_ID, context.getString(R.string.tropa_notify_lost_title), context.getString(R.string.tropa_notify_lost_text), timeoutMs = null)

    fun notifyRestored(context: Context) = post(
        context, CHANNEL_ID, NOTIFICATION_ID,
        context.getString(R.string.tropa_notify_restored_title), context.getString(R.string.tropa_notify_restored_text),
        timeoutMs = RESTORED_TIMEOUT_MS,
    )

    /** Opens the main screen, which offers the update. */
    fun notifyUpdate(context: Context, version: String) = post(
        context, UPDATE_CHANNEL_ID, UPDATE_NOTIFICATION_ID,
        context.getString(R.string.tropa_update_notify_title), context.getString(R.string.tropa_update_notify_text, version),
        timeoutMs = null,
    )

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, timeoutMs: Long?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context, channel)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, TropaMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (timeoutMs != null) setTimeoutAfter(timeoutMs) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications were revoked meanwhile: nothing to show.
        }
    }

    private fun ensureChannel(context: Context, channel: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channel) != null) return
        val name = context.getString(if (channel == UPDATE_CHANNEL_ID) R.string.tropa_update_channel else R.string.tropa_notify_channel)
        manager.createNotificationChannel(
            NotificationChannel(channel, name, NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
