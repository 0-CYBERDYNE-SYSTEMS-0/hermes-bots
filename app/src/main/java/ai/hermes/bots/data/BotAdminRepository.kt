package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.HermesGateway
import kotlinx.serialization.json.JsonObject
import ai.hermes.bots.protocol.RpcException
import kotlinx.coroutines.flow.first

/** Bot create/describe/configure/avatar RPCs against one gateway (PROTOCOL.md §5.3). */
class BotAdminRepository(private val manager: GatewayManager) {

    private suspend fun gateway(connectionId: String): HermesGateway =
        manager.live.first()[connectionId]?.gateway
            ?: throw IllegalStateException("connection $connectionId is not live")

    suspend fun create(
        connectionId: String,
        name: String,
        description: String? = null,
        cloneFrom: String? = null,
        cloneAll: Boolean = false,
        soul: String? = null,
        model: String? = null,
        provider: String? = null,
    ): JsonObject {
        val gw = gateway(connectionId)
        return try {
            gw.request(Catalog.METHOD_PROFILES_CREATE, BotAdmin.createParams(name, description, cloneFrom, cloneAll, soul, model, provider), 120_000)
        } catch (e: RpcException) {
            throw mapProfileError(e)
        }
    }

    suspend fun describe(connectionId: String, name: String): BotAdmin.DescribeSnapshot =
        BotAdmin.parseDescribe(
            gateway(connectionId).request(Catalog.METHOD_PROFILES_DESCRIBE, BotAdmin.describeParams(name), 60_000),
        )

    /** configure with ui_meta CAS: pass expectedRevisions from the roster row; on CAS failure the
     *  server rejects with 5064 — callers re-read (roster poll) and retry with fresh revisions. */
    suspend fun configure(
        connectionId: String,
        name: String,
        uiMeta: JsonObject? = null,
        expectedRevisions: Map<String, Int>? = null,
        soul: String? = null,
        description: String? = null,
        model: String? = null,
        provider: String? = null,
        confirmExpensiveModel: Boolean = false,
        disabledSkills: List<String>? = null,
        enabledToolsets: List<String>? = null,
        enabledMcpServers: List<String>? = null,
    ): BotAdmin.ConfigureResult {
        val gw = gateway(connectionId)
        return try {
            BotAdmin.parseConfigure(
                gw.request(
                    Catalog.METHOD_PROFILES_CONFIGURE,
                    BotAdmin.configureParams(
                        name, uiMeta, expectedRevisions, soul, description, model, provider,
                        confirmExpensiveModel, disabledSkills, enabledToolsets, enabledMcpServers,
                    ),
                    120_000,
                ),
            )
        } catch (e: RpcException) {
            throw mapProfileError(e)
        }
    }

    suspend fun setAvatar(connectionId: String, name: String, dataUrl: String): JsonObject {
        val safe = BotAdmin.validateAvatarDataUrl(dataUrl)
            ?: throw IllegalArgumentException("avatar must be a png/jpeg/webp data URL ≤2MB")
        return gateway(connectionId).request(Catalog.METHOD_PROFILES_SET_ASSET, BotAdmin.setAssetParams(name, safe), 60_000)
    }

    private fun mapProfileError(e: RpcException): RpcException = e
}
