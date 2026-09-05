package ai.hermes.bots

import ai.hermes.bots.data.ConnectionRepository
import ai.hermes.bots.data.CronRepository
import ai.hermes.bots.data.GatewayManager
import ai.hermes.bots.data.GroupRepository
import ai.hermes.bots.data.RelayEngine
import ai.hermes.bots.data.RosterRepository
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

    private val notifier = BotNotifier(app)
    private val notifyJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun start() {
        scope.launch { connections.ensureSeed() }
        gateways.start()
        roster.start()
        cron.start()
        relay.start()
        notifier.ensureChannel()
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
                                if (ev.type == Catalog.EVENT_MESSAGE_COMPLETE && app.activitiesInForeground == 0) {
                                    val text = (ev.payload["text"] as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.takeIf { it.isString }?.content.orEmpty()
                                    if (text.isNotBlank()) {
                                        val bot = roster.roster.value.firstOrNull { it.bot.canonicalSessionId == ev.sessionId }
                                        notifier.notifyBotMessage(
                                            bot?.bot?.displayName ?: bot?.bot?.name ?: "Hermes Bots",
                                            text,
                                            ev.sessionId ?: "global",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
