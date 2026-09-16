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

    // --- model catalog / keys (MODEL-UX-PUNCHLIST.md rev 2 / P1, P3, P7, R9) ---

    /**
     * `model.options` with picker hints (`{include_unconfigured: true}`, tui_gateway/
     * methods_complete.py:277-286; handler exceptions surface as 5033). Parsing stays in
     * [ModelCatalog] (pure) so the editor VM and tests can use it without a gateway.
     * `refresh = true` forces a server-side catalog rebuild (R8).
     */
    suspend fun modelOptions(connectionId: String, refresh: Boolean = false): JsonObject =
        gateway(connectionId).request(
            Catalog.METHOD_MODEL_OPTIONS,
            BotAdmin.modelOptionsParams(refresh = refresh),
            60_000,
        )

    /**
     * `model.save_key` `{slug, api_key}` (tui_gateway/methods_complete.py:289-320) with humane
     * error mapping for the editor's key-paste sheet (P7). Note the server's
     * `authenticated: true` reply is SYNTHETIC — any string flips it — so presence is not
     * validity; a verification turn (ModelVerifier) is the real test (A3/R7).
     */
    suspend fun saveKey(connectionId: String, slug: String, apiKey: String): JsonObject {
        return try {
            gateway(connectionId).request(
                Catalog.METHOD_MODEL_SAVE_KEY,
                BotAdmin.saveKeyParams(slug, apiKey),
                60_000,
            )
        } catch (e: RpcException) {
            throw when (e.code) {
                ERR_KEY_REQUIRED -> IllegalStateException("Enter a key first", e)
                ERR_KEY_UNKNOWN_PROVIDER -> IllegalStateException("This gateway doesn't accept keys for that provider here", e)
                ERR_KEY_NOT_API_AUTH -> IllegalStateException("That provider uses sign-in, not a key", e)
                ERR_KEY_NO_ENV_VAR -> IllegalStateException("This provider can't take a key from the app", e)
                ERR_KEY_MANAGED_INSTALL -> IllegalStateException("This gateway manages credentials read-only", e)
                else -> e
            }
        }
    }

    /**
     * Configure variant that also reports whether the model pin actually landed (R9:
     * `applied.model`, tui_gateway/methods_profiles.py:483-502). The existing [configure]
     * return type is unchanged for BotEditorViewModel compatibility.
     */
    suspend fun configureOutcome(
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
    ): BotAdmin.ConfigureOutcome {
        val gw = gateway(connectionId)
        return try {
            BotAdmin.parseConfigureOutcome(
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

    private fun mapProfileError(e: RpcException): RpcException = e

    private companion object {
        // model.save_key error codes (tui_gateway/methods_complete.py:289-320).
        const val ERR_KEY_REQUIRED = 4001
        const val ERR_KEY_UNKNOWN_PROVIDER = 4002
        const val ERR_KEY_NOT_API_AUTH = 4003
        const val ERR_KEY_NO_ENV_VAR = 4004
        const val ERR_KEY_MANAGED_INSTALL = 4006
    }
}
