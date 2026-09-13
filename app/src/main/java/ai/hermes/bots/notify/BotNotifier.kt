package ai.hermes.bots.notify

import ai.hermes.bots.MainActivity
import ai.hermes.bots.data.PendingApproval
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

/**
 * FCM-free local notifications (UI-SPEC.md §4.7): four channels — approvals (HIGH,
 * with Approve/Deny quick actions), bot replies (DEFAULT), routines (DEFAULT), system
 * (LOW). No sticky "connected" notification.
 */
class BotNotifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Bots waiting on your approval"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BOT_REPLIES, "Bot replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New messages from your bots"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ROUTINES, "Routines", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scheduled routine updates"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYSTEM, "System", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Connection and fleet notices"
            },
        )
        // §4.7 channel split replaces v1.0's single "bot messages" channel.
        manager.deleteNotificationChannel(CHANNEL_LEGACY)
    }

    private fun postAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyBotMessage(
        botLabel: String,
        preview: String,
        sessionId: String,
        connectionId: String? = null,
        botName: String? = null,
    ) {
        if (!postAllowed()) return // permission not granted yet; MainActivity requests it
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
        val notification = NotificationCompat.Builder(context, CHANNEL_BOT_REPLIES)
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

    /**
     * Approval prompt while the app is backgrounded (UI-SPEC.md §4.6/§4.7): renders the
     * command/question with up to two quick actions taken VERBATIM from the payload
     * `choices`, each wired to ApprovalActionReceiver → approval.respond; tapping the
     * body deep-links to the bot's chat. No choices → body-only notification.
     */
    fun notifyApproval(pending: PendingApproval, botLabel: String) {
        if (!postAllowed()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CONNECTION_ID, pending.connectionId)
            putExtra(EXTRA_BOT_NAME, pending.botName)
        }
        val contentIntent = PendingIntent.getActivity(
            context, pending.card.requestId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(botLabel)
            .setContentText(pending.card.command?.take(180) ?: "Wants your approval")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        pending.card.choices.take(2).forEach { choice ->
            val actionIntent = Intent(context, ApprovalActionReceiver::class.java).apply {
                putExtra(EXTRA_CONNECTION_ID, pending.connectionId)
                putExtra(EXTRA_REQUEST_ID, pending.card.requestId)
                putExtra(EXTRA_CHOICE, choice)
                putExtra(EXTRA_SESSION_ID, pending.sessionId)
                putExtra(EXTRA_BOT_NAME, pending.botName)
                putExtra(EXTRA_KIND, pending.card.kind)
            }
            val actionPending = PendingIntent.getBroadcast(
                context,
                (pending.card.requestId + "#" + choice).hashCode(),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, choice, actionPending)
        }
        try {
            NotificationManagerCompat.from(context).notify(pending.card.requestId.hashCode(), builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** Resolve cleanup: drop the approval notification for [requestId] from the shade. */
    fun cancelApproval(requestId: String) {
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
    }

    companion object {
        // Legacy v1.0 channel id — kept only for the historical test contract; unused
        // since the §4.7 channel split (deleted from the shade by ensureChannels).
        const val CHANNEL_LEGACY = "bot_messages"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_BOT_REPLIES = "bot_replies"
        const val CHANNEL_ROUTINES = "routines"
        const val CHANNEL_SYSTEM = "system"

        @Deprecated("Replaced by CHANNEL_BOT_REPLIES (UI-SPEC.md §4.7)")
        const val CHANNEL_ID = CHANNEL_LEGACY

        const val EXTRA_CONNECTION_ID = "hermesbots.extra.CONNECTION_ID"
        const val EXTRA_BOT_NAME = "hermesbots.extra.BOT_NAME"
        const val EXTRA_REQUEST_ID = "hermesbots.extra.REQUEST_ID"
        const val EXTRA_CHOICE = "hermesbots.extra.CHOICE"
        const val EXTRA_SESSION_ID = "hermesbots.extra.SESSION_ID"
        const val EXTRA_KIND = "hermesbots.extra.KIND"
    }
}
