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

/** Silent notifications about losing and regaining the connection (own channel, no sound). */
object TropaNotifier {
    private const val CHANNEL_ID = "tropa_connection"
    private const val NOTIFICATION_ID = 4201
    private const val RESTORED_TIMEOUT_MS = 10_000L

    fun notifyLost(context: Context) = post(context, R.string.tropa_notify_lost_title, R.string.tropa_notify_lost_text, timeoutMs = null)

    fun notifyRestored(context: Context) =
        post(context, R.string.tropa_notify_restored_title, R.string.tropa_notify_restored_text, timeoutMs = RESTORED_TIMEOUT_MS)

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, title: Int, text: Int, timeoutMs: Long?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, TropaMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (timeoutMs != null) setTimeoutAfter(timeoutMs) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications were revoked meanwhile: nothing to show.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.tropa_notify_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
