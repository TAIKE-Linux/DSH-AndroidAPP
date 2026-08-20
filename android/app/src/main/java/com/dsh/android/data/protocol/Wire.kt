package com.dsh.android.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Wire envelopes of the DeepSeek Harness four-quadrant RPC model
 * (official contract: @deepseek-ai/dsh-host-apiproxy / dsh-client-connection).
 *
 *   Client -> Host unary : POST /api/<method>  body ClientRequest
 *   Host  -> Client unary : HTTP response body ServerResponse
 *   Host  -> Client push  : WebSocket text frame ServerRequest (payload = frame)
 *   Client -> Host answer : POST /api/respond  body ClientResponse
 */
@Serializable
data class RpcError(
    val code: String,
    val message: String,
    val details: JsonElement = JsonNull,
)

@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonElement = JsonNull,
    val error: RpcError? = null,
)

@Serializable
data class ServerResponse(
    val type: String = "server-response",
    @SerialName("rpcId") val rpcId: String,
    val result: RpcResult,
)

@Serializable
data class ServerRequest(
    val type: String = "server-request",
    @SerialName("rpcId") val rpcId: String,
    val method: String,
    val payload: JsonElement,
)

@Serializable
data class RpcReceipt(
    val accepted: Boolean,
    val reason: String? = null,
)

/** Thrown when an RPC succeeds at transport level but the business result is an error. */
class RpcBusinessException(
    val code: String,
    override val message: String,
    val details: JsonElement = JsonNull,
) : Exception(message)

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun newRpcId(): String = UUID.randomUUID().toString()

    fun clientRequest(rpcId: String, method: String, payload: JsonElement): String = buildJsonObject {
        put("type", "client-request")
        put("rpcId", rpcId)
        put("method", method)
        put("payload", payload)
    }.toString()

    fun clientResponse(rpcId: String, value: JsonElement): String = buildJsonObject {
        put("type", "client-response")
        put("rpcId", rpcId)
        put("result", buildJsonObject {
            put("ok", true)
            put("value", value)
        })
    }.toString()

    fun obj(vararg pairs: Pair<String, JsonElement?>): JsonElement = buildJsonObject {
        for ((k, v) in pairs) if (v != null) put(k, v)
    }
}

/** Unwrap a ServerResponse into its business value or throw RpcBusinessException. */
fun ServerResponse.unwrap(): JsonElement {
    val r = result
    if (r.ok) return r.value
    throw RpcBusinessException(r.error?.code ?: "internal", r.error?.message ?: "unknown error", r.error?.details ?: JsonNull)
}
