package ai.hermes.bots.notify

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.PendingApproval
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Notification quick actions for approval prompts (UI-SPEC.md §4.7): sends
 * approval.respond / clarify.respond through the owning gateway, cancels the
 * notification, then finishes the goAsync result.
 *
 * Process-death safe: missing extras are a no-op, and a rebuilt (empty) inbox still
 * answers via a pending entry synthesized from the intent, so the RPC fires even though
 * the in-memory list was lost with the old process.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connectionId = intent.getStringExtra(BotNotifier.EXTRA_CONNECTION_ID) ?: return
        val requestId = intent.getStringExtra(BotNotifier.EXTRA_REQUEST_ID) ?: return
        val choice = intent.getStringExtra(BotNotifier.EXTRA_CHOICE) ?: return
        val app = context.applicationContext as? HermesBotsApp ?: return

        val pendingApproval = app.graph.inbox.pending.value.firstOrNull { it.card.requestId == requestId }
            ?: PendingApproval(
                connectionId = connectionId,
                sessionId = intent.getStringExtra(BotNotifier.EXTRA_SESSION_ID),
                botName = intent.getStringExtra(BotNotifier.EXTRA_BOT_NAME),
                card = ApprovalCard(
                    requestId = requestId,
                    kind = intent.getStringExtra(BotNotifier.EXTRA_KIND) ?: "approval.request",
                    command = null,
                    choices = listOf(choice),
                ),
                atMs = System.currentTimeMillis(),
            )

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Stay inside the broadcast window (~10 s); a dead gateway fails fast and
                // the inbox entry survives for a retry from the Activity screen.
                withTimeout(9_000) { app.graph.inbox.respond(pendingApproval, choice) }
            } catch (_: Exception) {
                // Respond failed — the notification is still dismissed; the Activity
                // screen keeps the row so the user can answer from there.
            } finally {
                NotificationManagerCompat.from(context).cancel(requestId.hashCode())
                result.finish()
            }
        }
    }
}
