package ai.hermes.bots

import ai.hermes.bots.data.ConnectionRepository
import ai.hermes.bots.data.CronRepository
import ai.hermes.bots.data.GatewayManager
import ai.hermes.bots.data.GroupRepository
import ai.hermes.bots.data.RosterRepository
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(app: HermesBotsApp) {
    val connections = ConnectionRepository(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val gateways = GatewayManager(connections, scope)
    val roster = RosterRepository(gateways, scope)
    val cron = CronRepository(gateways, scope)
    val groups = GroupRepository(gateways)

    fun start() {
        scope.launch { connections.ensureSeed() }
        gateways.start()
        roster.start()
        cron.start()
    }
}

class HermesBotsApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}
