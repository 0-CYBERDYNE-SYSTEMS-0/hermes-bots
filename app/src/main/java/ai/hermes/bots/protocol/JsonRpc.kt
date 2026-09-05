package ai.hermes.bots.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ProtocolException(message: String) : Exception(message)

sealed interface RpcId {
    data class Num(val value: Long) : RpcId
    data class Str(val value: String) : RpcId
}

data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

class RpcException(val error: RpcError) : Exception("rpc ${error.code}: ${error.message}") {
    val code: Int get() = error.code
    fun isMethodNotFound(): Boolean = code == Catalog.ERR_METHOD_NOT_FOUND
}

sealed interface RpcFrame
data class RpcResponse(val id: RpcId, val result: JsonElement?, val error: RpcError?) : RpcFrame
data class RpcPush(val method: String, val params: JsonObject) : RpcFrame
data class RpcServerRequest(val id: RpcId, val method: String, val params: JsonObject) : RpcFrame

object JsonRpc {
    val json: Json = Json { ignoreUnknownKeys = true }

    /** One JSON object per text frame; params must ALWAYS be an object (PROTOCOL.md §4). */
    fun encodeRequest(id: RpcId, method: String, params: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put(
                "id",
                when (id) {
                    is RpcId.Num -> JsonPrimitive(id.value)
                    is RpcId.Str -> JsonPrimitive(id.value)
                },
            )
            put("method", method)
            put("params", params)
        }.toString()

    fun decode(text: String): RpcFrame {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolException("unparseable frame: ${e.message}")
        }
        val id = root["id"]?.takeIf { it !is JsonNull }?.let { parseId(it) }
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            method != null && id != null -> RpcServerRequest(id, method, root.paramsObject())
            method != null -> RpcPush(method, root.paramsObject())
            id != null -> RpcResponse(
                id = id,
                result = root["result"]?.takeIf { it !is JsonNull },
                error = root["error"]?.takeIf { it !is JsonNull }?.let { parseError(it) },
            )
            else -> throw ProtocolException("frame has neither method nor id")
        }
    }

    private fun JsonObject.paramsObject(): JsonObject =
        this["params"] as? JsonObject ?: JsonObject(emptyMap())

    private fun parseId(element: JsonElement): RpcId {
        val p = element as? JsonPrimitive ?: throw ProtocolException("id must be number or string")
        return if (p.isString) {
            RpcId.Str(p.content)
        } else {
            p.content.toLongOrNull()?.let { RpcId.Num(it) }
                ?: throw ProtocolException("id must be number or string")
        }
    }

    private fun parseError(element: JsonElement): RpcError {
        val obj = element as? JsonObject ?: throw ProtocolException("error must be object")
        val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw ProtocolException("error.code missing")
        val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return RpcError(code, message, obj["data"]?.takeIf { it !is JsonNull })
    }
}
