package ai.hermes.bots

import ai.hermes.bots.data.ConnectionRepository
import ai.hermes.bots.data.AnyChatRepository
import ai.hermes.bots.data.ApprovalInbox
import ai.hermes.bots.data.ApprovalRpcSender
import ai.hermes.bots.data.CanonicalChat
import ai.hermes.bots.data.CronRepository
import ai.hermes.bots.data.FleetProvisioningCoordinator
import ai.hermes.bots.data.GatewayManager
import ai.hermes.bots.data.GroupRepository
import ai.hermes.bots.data.ModelHealthStore
import ai.hermes.bots.data.ModelVerifier
import ai.hermes.bots.data.RelayEngine
import ai.hermes.bots.data.RosterRepository
import ai.hermes.bots.data.SettingsRepository
import ai.hermes.bots.notify.BotNotifier
import ai.hermes.bots.protocol.Catalog
import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(private val app: HermesBotsApp) {
    val connections = ConnectionRepository(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val gateways = GatewayManager(connections, scope)
    val roster = RosterRepository(gateways, scope)
    val cron = CronRepository(gateways, scope)
    val groups = GroupRepository(gateways)
    val relay = RelayEngine(gateways, scope)
    val settings = SettingsRepository(app)
    // Model verification (MODEL-UX-PUNCHLIST.md W3): health cache + turn-based verifier.
    val modelHealth = ModelHealthStore(app)
    val modelVerifier = ModelVerifier(gateways, modelHealth)
    val provisioning = FleetProvisioningCoordinator(connections)
    val anyChat = AnyChatRepository(app, gateways, roster, scope)

    /**
     * Cross-connection "Needs you" inbox (UI-SPEC.md §4.6): blocking-prompt events land
     * here; respond rides the owning connection's gateway socket.
     */
    val inbox = ApprovalInbox(sender = ApprovalRpcSender { connectionId, method, params ->
        val conn = gateways.live.value[connectionId]
            ?: throw IllegalStateException("connection $connectionId is not live")
        conn.gateway.request(method, params)
    })

    private val notifier = BotNotifier(app)
    private val notifyJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun start() {
        scope.launch { connections.ensureSeed() }
        gateways.start()
        roster.start()
        cron.start()
        relay.start()
        notifier.ensureChannels()
        scope.launch {
            gateways.live.collect { live ->
                // Keyed per connection so reconnects never stack duplicate collectors.
                notifyJobs.keys.filterNot { it in live }.forEach { id ->
                    notifyJobs.remove(id)?.cancel()
                }
                live.forEach { (id, conn) ->
                    if (notifyJobs[id] == null) {
                        notifyJobs[id] = scope.launch {
                            conn.gateway.events.collect { ev ->
                                if (ev.type == Catalog.EVENT_MESSAGE_COMPLETE) {
                                    val text = (ev.payload["text"] as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.takeIf { it.isString }?.content.orEmpty()
                                    if (text.isNotBlank()) {
                                        val bot = roster.roster.value.firstOrNull { it.bot.canonicalSessionId == ev.sessionId }
                                        // R4 (MODEL-UX-PUNCHLIST.md): only roster-resolved
                                        // sessions notify — hidden sessions (the model
                                        // verifier's test turns, stray tool sessions) used to
                                        // land here labeled "Hermes Bots" and spammed the feed.
                                        if (bot != null) {
                                            val label = bot.bot.displayName ?: bot.bot.name
                                            // In-app history records every qualifying event, even when
                                            // the system notification is suppressed or app is foregrounded.
                                            // Ids let the Notifications list deep-link back to the chat (SV-15).
                                            settings.appendNotification(
                                                botLabel = label,
                                                preview = text,
                                                sessionId = ev.sessionId ?: "global",
                                                connectionId = bot.bot.connectionId,
                                                botName = bot.bot.name,
                                            )
                                            if (app.activitiesInForeground == 0 && settings.notificationsEnabled.value) {
                                                notifier.notifyBotMessage(
                                                    label,
                                                    text,
                                                    ev.sessionId ?: "global",
                                                    bot.bot.connectionId,
                                                    bot.bot.name,
                                                )
                                            }
                                        }
                                    }
                                }
                                // Blocking prompts (approval/clarify/sudo/secret) + expiry
                                // (PROTOCOL.md §5.5) feed the Activity screen's Needs-you
                                // inbox; while backgrounded they also raise a HIGH notification
                                // with verbatim quick actions (UI-SPEC.md §4.6/§4.7).
                                when (ev.type) {
                                    Catalog.EVENT_APPROVAL_REQUEST,
                                    Catalog.EVENT_CLARIFY_REQUEST,
                                    Catalog.EVENT_SUDO_REQUEST,
                                    Catalog.EVENT_SECRET_REQUEST,
                                    -> {
                                        val card = CanonicalChat.parseCard(ev.type, ev.payload) ?: return@collect
                                        val bot = roster.roster.value.firstOrNull { it.bot.canonicalSessionId == ev.sessionId }
                                        val label = bot?.bot?.displayName ?: bot?.bot?.name ?: "Hermes Bots"
                                        val stored = inbox.onBlockingPrompt(
                                            connectionId = id,
                                            botName = bot?.bot?.name,
                                            sessionId = ev.sessionId,
                                            card = card,
                                        )
                                        if (stored != null &&
                                            app.activitiesInForeground == 0 &&
                                            settings.notificationsEnabled.value
                                        ) {
                                            notifier.notifyApproval(stored, label)
                                        }
                                    }
                                    Catalog.EVENT_APPROVAL_EXPIRE,
                                    Catalog.EVENT_CLARIFY_EXPIRE,
                                    Catalog.EVENT_SUDO_EXPIRE,
                                    Catalog.EVENT_SECRET_EXPIRE,
                                    -> {
                                        val requestId =
                                            (ev.payload["request_id"] as? kotlinx.serialization.json.JsonPrimitive)
                                                ?.takeIf { it.isString }?.content
                                        if (requestId != null) inbox.onExpire(requestId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Resolving removes it everywhere (UI-SPEC.md §4.6): when a request leaves the
        // inbox (answered via chat, quick action, or notification), retire its system
        // notification too.
        scope.launch {
            var known = inbox.pending.value.map { it.card.requestId }.toSet()
            inbox.pending.collect { rows ->
                val ids = rows.map { it.card.requestId }.toSet()
                (known - ids).forEach { notifier.cancelApproval(it) }
                known = ids
            }
        }
    }
}

class HermesBotsApp : Application() {
    lateinit var graph: AppGraph
        private set

    var activitiesInForeground: Int = 0
        private set

    private val lifecycleTracker = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { activitiesInForeground += 1 }
        override fun onActivityStopped(activity: Activity) { activitiesInForeground = (activitiesInForeground - 1).coerceAtLeast(0) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityDestroyed(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(lifecycleTracker)
        graph = AppGraph(this)
        graph.start()
    }
}
