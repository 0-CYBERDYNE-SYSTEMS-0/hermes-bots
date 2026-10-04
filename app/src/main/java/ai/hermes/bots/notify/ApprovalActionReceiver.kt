package ai.hermes.bots.notify

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.protocol.Catalog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Notification quick actions for approval prompts: sends
 * approval.respond / clarify.respond through the owning gateway, cancels the
 * notification on success, then finishes the goAsync result.
 *
 * When the receiver starts with an empty inbox, it restores the row from action extras
 * before sending; a failed response stays retryable in-app and in the notification shade.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connectionId = intent.getStringExtra(BotNotifier.EXTRA_CONNECTION_ID) ?: return
        val requestId = intent.getStringExtra(BotNotifier.EXTRA_REQUEST_ID) ?: return
        val choice = intent.getStringExtra(BotNotifier.EXTRA_CHOICE) ?: return
        val serverRequestId = intent.getStringExtra(BotNotifier.EXTRA_SERVER_REQUEST_ID)
        val qid = intent.getStringExtra(BotNotifier.EXTRA_QID)
        val app = context.applicationContext as? HermesBotsApp ?: return

        val inbox = app.graph.inbox
        val pendingApproval = inbox.pending.value.firstOrNull { it.card.requestId == requestId }
            ?: inbox.onBlockingPrompt(
                connectionId = connectionId,
                sessionId = intent.getStringExtra(BotNotifier.EXTRA_SESSION_ID),
                botName = intent.getStringExtra(BotNotifier.EXTRA_BOT_NAME),
                card = ApprovalCard(
                    requestId = requestId,
                    kind = intent.getStringExtra(BotNotifier.EXTRA_KIND) ?: "approval.request",
                    command = intent.getStringExtra(BotNotifier.EXTRA_APPROVAL_COMMAND),
                    choices = intent.getStringArrayListExtra(BotNotifier.EXTRA_APPROVAL_CHOICES)
                        ?.takeIf { it.isNotEmpty() } ?: listOf(choice),
                    serverRequestId = serverRequestId,
                    qid = qid,
                ),
            ) ?: return

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var responseSucceeded = false
            try {
                // Stay inside the broadcast window (~10 s); a dead gateway fails fast and
                // the inbox entry survives for a retry from the Activity screen.
                withTimeout(9_000) {
                    val serverId = pendingApproval.card.serverRequestId
                    if (serverId == null) {
                        inbox.respond(pendingApproval, choice)
                    } else {
                        val connection = app.graph.gateways.live.value[pendingApproval.connectionId]
                            ?: throw IllegalStateException("connection is not live")
                        connection.gateway.respondServerRequest(
                            serverId,
                            serverRequestResult(pendingApproval.card, choice),
                        )
                        inbox.remove(pendingApproval.card.requestId)
                    }
                }
                responseSucceeded = true
            } catch (_: Exception) {
                // Re-post from the saved action metadata: the action may have been
                // dismissed by Android, and the rebuilt inbox now keeps the full card.
                val botLabel = intent.getStringExtra(BotNotifier.EXTRA_BOT_LABEL)
                    ?: pendingApproval.botName
                    ?: "Hermes Bots"
                BotNotifier(context).notifyApproval(pendingApproval, botLabel)
            } finally {
                if (responseSucceeded) {
                    NotificationManagerCompat.from(context).cancel(requestId.hashCode())
                }
                result.finish()
            }
        }
    }

    private fun serverRequestResult(card: ApprovalCard, choice: String) = when (card.kind) {
        Catalog.EVENT_CLARIFY_REQUEST -> if (card.qid == null) {
            buildJsonObject { put("answer", choice) }
        } else {
            buildJsonObject {
                put("answers", buildJsonObject { put(card.qid, choice) })
            }
        }
        Catalog.EVENT_SECRET_REQUEST -> buildJsonObject { put("answer", choice) }
        else -> buildJsonObject { put("choice", choice) }
    }
}
