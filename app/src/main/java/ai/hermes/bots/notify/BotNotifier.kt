package ai.hermes.bots.notify

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
import ai.hermes.bots.MainActivity

/** FCM-free local notifications for bot messages that arrive while the app is backgrounded. */
class BotNotifier(private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bot messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "New messages from your bots"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyBotMessage(
        botLabel: String,
        preview: String,
        sessionId: String,
        connectionId: String? = null,
        botName: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // permission not granted yet; MainActivity requests it
        }
        // Explicit target + chat extras: tapping the notification lands in that bot's
        // conversation (SV-15) instead of a plain app relaunch. SINGLE_TOP routes through
        // onNewIntent when the activity is already up.
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CONNECTION_ID, connectionId)
            putExtra(EXTRA_BOT_NAME, botName)
        }
        val pending = PendingIntent.getActivity(
            context, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(botLabel)
            .setContentText(preview.take(180))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(sessionId.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL_ID = "bot_messages"
        const val EXTRA_CONNECTION_ID = "hermesbots.extra.CONNECTION_ID"
        const val EXTRA_BOT_NAME = "hermesbots.extra.BOT_NAME"
    }
}
