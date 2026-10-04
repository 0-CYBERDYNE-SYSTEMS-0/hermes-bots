package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

data class SlashCommand(val name: String, val argument: String) {
    val wireCommand: String get() = listOf(name, argument).filter { it.isNotBlank() }.joinToString(" ")
}

sealed interface SlashDirective {
    data class Output(val text: String, val warning: String? = null) : SlashDirective
    data class Alias(val target: String) : SlashDirective
    data class Submit(val message: String, val display: String?, val notice: String?) : SlashDirective
    data class Prefill(val message: String, val notice: String?) : SlashDirective
}

/** Canonical "Bot Chat" helpers for the gateway contract in PROTOCOL.md §§5.2-5.5. */
object CanonicalChat {

    /** Parse the gateway's `/name argument` form; prose containing a slash stays a prompt. */
    fun parseSlashCommand(text: String): SlashCommand? {
        val command = text.trim()
        if (!command.startsWith('/') || command.startsWith("//")) return null
        val body = command.drop(1)
        val name = body.takeWhile { !it.isWhitespace() }
        if (name.isBlank() || '/' in name) return null
        return SlashCommand(name.lowercase(Locale.ROOT), body.drop(name.length).trim())
    }

    fun isCompressionPreview(argument: String): Boolean =
        argument.split(Regex("\\s+")).any {
            it.equals("--preview", ignoreCase = true) ||
                it.equals("--dry-run", ignoreCase = true) ||
                it.equals("--dryrun", ignoreCase = true)
        }

    /** Only retry slash routing when the first RPC definitely did not run a command. */
    fun shouldFallbackSlashDispatch(error: RpcException): Boolean =
        error.isMethodNotFound() ||
            (error.code == 4018 && error.error.message.contains("use command.dispatch", ignoreCase = true))

    /** Typing /new inside a canonical chat is rerouted to session.compress — never a fork. */
    fun isOpenCommand(text: String): Boolean =
        parseSlashCommand(text)?.let { it.name == "new" && it.argument.isBlank() } == true

    /**
     * New Bot Chat. [model] and [provider] are the bot's saved pin.
     * A registry pin is sent on the create so the session does not start on the
     * gateway default. A `custom*` pin is not sent: session overrides cannot
     * resolve those, and [follow_profile_config] makes the gateway use the profile.
     */
    fun createParams(profile: String, model: String? = null, provider: String? = null): JsonObject = buildJsonObject {
        put("title", Catalog.CANONICAL_CHAT_TITLE)
        put("hidden", true)
        put("profile", profile)
        put("close_on_disconnect", false) // Keep the canonical session across mobile disconnects.
        put("follow_profile_config", true)
        val pinModel = model?.trim().orEmpty()
        val pinProvider = provider?.trim().orEmpty()
        if (pinModel.isNotBlank() && pinProvider.isNotBlank() && !pinProvider.lowercase().startsWith("custom")) {
            put("model", pinModel)
            put("provider", pinProvider)
        }
    }

    /** Model and provider named by a session.create / session.resume `info` object. */
    fun runningPin(result: JsonObject): Pair<String, String> {
        val info = result["info"] as? JsonObject ?: return "" to ""
        fun str(key: String) =
            (info[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        return str("model") to str("provider")
    }

    /**
     * The opened session names a different model or provider than the bot's saved pin.
     * A blank session half is not a mismatch: the gateway has not reported that half.
     */
    fun pinMismatch(
        sessionModel: String,
        sessionProvider: String,
        botModel: String?,
        botProvider: String?,
    ): Boolean {
        val wantModel = botModel?.trim().orEmpty()
        val wantProvider = botProvider?.trim().orEmpty()
        if (wantModel.isBlank() || wantProvider.isBlank()) return false
        val modelDiffers = sessionModel.isNotBlank() && !sessionModel.equals(wantModel, ignoreCase = true)
        val providerDiffers = sessionProvider.isNotBlank() && !sessionProvider.equals(wantProvider, ignoreCase = true)
        return modelDiffers || providerDiffers
    }

    /** `config.set` bodies, strictest contract first. */
    fun switchAttempts(
        sessionId: String,
        profile: String,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
    ): List<JsonObject> {
        val withSession = "$model --provider $provider --session"
        val plain = "$model --provider $provider"
        return listOf(withSession, plain).flatMap { value ->
            listOf(
                switchBody(
                    sessionId,
                    profile,
                    value,
                    includeProfile = true,
                    confirmExpensiveModel = confirmExpensiveModel,
                ),
                switchBody(
                    sessionId,
                    profile,
                    value,
                    includeProfile = false,
                    confirmExpensiveModel = confirmExpensiveModel,
                ),
            )
        }
    }

    private fun switchBody(
        sessionId: String,
        profile: String,
        value: String,
        includeProfile: Boolean,
        confirmExpensiveModel: Boolean,
    ): JsonObject = buildJsonObject {
        put("key", "model")
        put("value", value)
        put("session_id", sessionId)
        if (includeProfile) put("profile", profile)
        if (confirmExpensiveModel) put("confirm_expensive_model", true)
    }

    fun isContractMismatch(message: String?): Boolean {
        val m = message?.lowercase().orEmpty()
        return "extra inputs" in m || "invalid params" in m
    }

    /** The gateway accepted the switch immediately or for the next turn. */
    fun switchAccepted(result: JsonObject): Boolean = bool(result, "confirm_required") != true

    fun switchRequiresConfirmation(result: JsonObject): Boolean = bool(result, "confirm_required") == true

    fun switchDeferred(result: JsonObject): Boolean = bool(result, "deferred") == true

    fun switchDetail(result: JsonObject): String? =
        str(result, "confirm_message") ?: str(result, "warning")

    data class AlignResult(
        val accepted: Boolean,
        val detail: String?,
        val confirmationRequired: Boolean = false,
        val deferred: Boolean = false,
        val outcomeUnknown: Boolean = false,
    )

    /**
     * Move [sessionId] onto the saved pin. Tries each [switchAttempts] body until one is accepted
     * or the gateway returns a real failure (not an unknown-field rejection).
     */
    suspend fun alignSession(
        sessionId: String,
        profile: String,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
        request: suspend (JsonObject) -> JsonObject,
    ): AlignResult {
        var detail: String? = null
        for (params in switchAttempts(sessionId, profile, provider, model, confirmExpensiveModel)) {
            try {
                val result = request(params)
                if (switchRequiresConfirmation(result)) {
                    return AlignResult(
                        false,
                        switchDetail(result) ?: "The gateway requires confirmation before switching this model.",
                        confirmationRequired = true,
                    )
                }
                if (switchDeferred(result)) return AlignResult(true, null, deferred = true)
                if (switchAccepted(result)) return AlignResult(true, null)
                return AlignResult(false, switchDetail(result) ?: "The gateway did not switch the model.")
            } catch (e: RpcException) {
                detail = e.message
                if (!isContractMismatch(e.message)) return AlignResult(false, detail)
            }
        }
        return AlignResult(false, detail ?: "The gateway did not switch the model.")
    }

    private fun bool(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    fun compressParams(sessionId: String, focusTopic: String? = null): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        focusTopic?.trim()?.takeIf { it.isNotEmpty() }?.let { put("focus_topic", it) }
    }

    fun slashExecParams(sessionId: String, command: SlashCommand): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("command", command.wireCommand)
    }

    fun commandDispatchParams(sessionId: String, command: SlashCommand): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("name", command.name)
        if (command.argument.isNotBlank()) put("arg", command.argument)
    }

    /** Decode the installed gateway's plain output or its documented dispatch directive. */
    fun parseSlashDirective(result: JsonObject): SlashDirective? {
        val type = str(result, "type")
        val warning = str(result, "warning")
        return when (type) {
            null -> {
                val output = str(result, "output")
                if (output != null || warning != null) SlashDirective.Output(output.orEmpty(), warning) else null
            }
            "exec", "plugin" -> SlashDirective.Output(str(result, "output").orEmpty(), warning)
            "alias" -> str(result, "target")?.let(SlashDirective::Alias)
            "send", "skill" -> str(result, "message")?.let {
                SlashDirective.Submit(it, str(result, "display"), str(result, "notice"))
            }
            "prefill" -> str(result, "message")?.let {
                SlashDirective.Prefill(it, str(result, "notice"))
            }
            else -> null
        }
    }

    fun compressionFeedback(result: JsonObject): String {
        val summary = result["summary"] as? JsonObject
        val headline = summary?.let { str(it, "headline") }
        val message = str(result, "message")
        val compressed = bool(result, "compressed") == true || str(result, "status") == "compressed"
        val lockHeld = bool(result, "lock_held") == true
        val noop = bool(summary ?: JsonObject(emptyMap()), "noop") == true
        if (lockHeld) return message ?: "Compression is already running for this conversation. No changes were made."
        if (noop) return message ?: headline ?: str(summary ?: JsonObject(emptyMap()), "note")
            ?: "Nothing was compressed; no context changes were needed."
        if (compressed) {
            val removed = (result["removed"] as? JsonPrimitive)?.content?.toIntOrNull()
            return buildList {
                add("Conversation compressed.")
                if (removed != null && removed > 0) add("Removed $removed older messages.")
                if (!headline.isNullOrBlank()) add(headline)
            }.joinToString("\n")
        }
        return message ?: headline ?: if (str(result, "status") == "pending") {
            "Compression is still running on the gateway. Reopen this chat to check its transcript."
        } else {
            "No context was compressed."
        }
    }

    fun submitParams(sessionId: String, text: String): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("text", text)
    }

    fun fileAttachParams(sessionId: String, dataUrl: String): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("data", dataUrl)
    }

    /**
     * approval.request / clarify.request / sudo.request / secret.request payload → card.
     *
     * Choices are never fabricated. Approval.request payloads always carry choices — the
     * server fills a missing set with [once (+session) (+always)
     * +deny] before emitting (hermes-agent tui_gateway/server.py:629-640 `_approval_request_payload`,
     * emit at :688; replay snapshot :671-679) — so a real approval never lands empty. Only a
     * free-text clarify arrives with no choices, and sending a fabricated "once" as the ANSWER
     * to an open question is wrong (the desktop shows a typed input there). Empty choices render
     * a humane "reply in chat" affordance instead (ApprovalCardView / Activity Needs-you).
     *
     * Accept the clarify shapes carried by the gateway:
     * - batch clarify: `questions: [{qid, question, choices, multi_select}]`
     *   (tui_gateway/server.py `_clarify_block`) — the card shows the first entry;
     * - plain `text`/`title` instead of `question`;
     * - the 0.21.3+ JSON-RPC dialect: HermesGateway re-emits those requests with
     *   `server_request: true` + request_id = srq id, and the card records it as
     *   [ApprovalCard.serverRequestId] so answers ride a result frame.
     */
    fun parseCard(kind: String, payload: JsonObject?): ApprovalCard? {
        if (payload == null) return null
        val requestId = stringField(payload, "request_id") ?: return null
        val batch = (payload["questions"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val questions = (payload["questions"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { question ->
                val qid = stringField(question, "qid") ?: return@mapNotNull null
                qid to (stringField(question, "question") ?: qid)
            }
        val command = stringField(payload, "command")
            ?: stringField(payload, "question")
            ?: stringField(payload, "text")
            ?: batch?.let { stringField(it, "question") }
        val choices = stringList(payload, "choices")
            ?: stringList(payload, "answers")
            ?: batch?.let { stringList(it, "choices") }
            ?: emptyList()
        val serverRequest = (payload["server_request"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        return ApprovalCard(
            requestId = requestId,
            kind = kind,
            command = command,
            choices = choices,
            serverRequestId = if (serverRequest) requestId else null,
            qid = batch?.let { stringField(it, "qid") },
            questions = questions,
        )
    }

    private fun stringField(payload: JsonObject, key: String): String? =
        (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringList(payload: JsonObject, key: String): List<String>? =
        (payload[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.takeIf { it.isNotEmpty() }
}
