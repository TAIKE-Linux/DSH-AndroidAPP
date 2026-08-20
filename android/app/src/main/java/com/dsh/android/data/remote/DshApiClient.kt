package com.dsh.android.data.remote

import com.dsh.android.data.protocol.Api
import com.dsh.android.data.protocol.HistoryPage
import com.dsh.android.data.protocol.HostDescription
import com.dsh.android.data.protocol.RpcBusinessException
import com.dsh.android.data.protocol.RpcReceipt
import com.dsh.android.data.protocol.ServerResponse
import com.dsh.android.data.protocol.SessionModels
import com.dsh.android.data.protocol.SessionSummary
import com.dsh.android.data.protocol.Wire
import com.dsh.android.data.protocol.unwrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URLEncoder

/** Thrown when the PC did not answer within the per-request time limit. */
private fun timeoutError(what: String): RpcBusinessException =
    RpcBusinessException("timeout", "$what：电脑端未在限定时间内响应，已自动重新连接（若电脑在忙，稍后重试即可）")

/**
 * Unary RPC + respond carrier for one DSH server (through the PC gateway).
 * Wire contract: POST /api/<method> with a client-request envelope; answerable
 * server frames via POST /api/respond with a client-response envelope.
 */
class DshApiClient(
    val baseUrl: String,
    val token: String,
    /** Client with a wall-clock callTimeout for unary RPCs. */
    private val rpc: OkHttpClient,
    /** Client with a generous callTimeout for file uploads. */
    private val upload: OkHttpClient,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    suspend fun unary(method: String, payload: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val rpcId = Wire.newRpcId()
        val request = authed("$baseUrl/api/$method")
            .post(Wire.clientRequest(rpcId, method, payload).toRequestBody(jsonMedia))
            .build()
        val response = try {
            rpc.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw timeoutError("请求超时")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw RpcBusinessException("unauthorized", "网关拒绝了 token（HTTP 401）")
            if (!resp.isSuccessful) throw RpcBusinessException("transport", "HTTP ${resp.code}: ${text.take(300)}")
            val envelope = runCatching { Wire.json.decodeFromString(ServerResponse.serializer(), text) }
                .getOrElse {
                    // HTTP 200 but the body is not a DSH envelope: the phone is
                    // almost certainly talking to the wrong service (wrong
                    // IP/port, router page, VPN interception). Surface the raw
                    // body so the user can tell what actually answered.
                    val preview = text.take(300).ifBlank { "（空响应体，连接可能被中途掐断）" }
                    throw RpcBusinessException(
                        "transport",
                        "invalid server-response envelope：响应不是 DSH 协议（可能连错了端口/设备，或被手机 VPN/代理劫持）。服务器实际返回：$preview",
                    )
                }
            if (envelope.rpcId != rpcId) throw RpcBusinessException("transport", "rpcId echo mismatch")
            envelope.unwrap()
        }
    }

    /** Answer an answerable server frame (approval/question). Returns true when accepted. */
    suspend fun respond(rpcId: String, value: JsonElement): Boolean = withContext(Dispatchers.IO) {
        val request = authed("$baseUrl/api/respond")
            .post(Wire.clientResponse(rpcId, value).toRequestBody(jsonMedia))
            .build()
        val response = try {
            rpc.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw timeoutError("应答超时")
        }
        response.use { resp ->
            if (resp.code == 401) throw RpcBusinessException("unauthorized", "网关拒绝了 token（HTTP 401）")
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RpcBusinessException("transport", "HTTP ${resp.code}")
            runCatching { Wire.json.decodeFromString(RpcReceipt.serializer(), text).accepted }.getOrDefault(false)
        }
    }

    // -- Convenience methods -------------------------------------------------

    suspend fun describe(): HostDescription {
        val value = unary("host.describe", Api.hostDescribe())
        return runCatching { Wire.json.decodeFromJsonElement(HostDescription.serializer(), value) }.getOrDefault(HostDescription())
    }

    suspend fun listSessions(): List<SessionSummary> {
        val value = unary("session.list", Api.sessionList())
        return runCatching {
            Wire.json.decodeFromJsonElement(SessionListPage.serializer(), value).items
        }.getOrDefault(emptyList())
    }

    suspend fun createSession(agentPreset: String? = null): String {
        val value = unary("session.create", Api.sessionCreate(agentPreset = agentPreset))
        return runCatching { Wire.json.decodeFromJsonElement(CreatedSession.serializer(), value).sessionId }
            .getOrElse { throw RpcBusinessException("transport", "session.create: unexpected response") }
    }

    suspend fun history(sessionId: String, beforeSeq: Long? = null, maxMessages: Int? = null): HistoryPage {
        val value = unary("session.history", Api.sessionHistory(sessionId, beforeSeq, maxMessages))
        return runCatching { Wire.json.decodeFromJsonElement(HistoryPage.serializer(), value) }
            .getOrElse { throw RpcBusinessException("transport", "session.history: unexpected response") }
    }

    suspend fun prompt(sessionId: String, text: String, mode: String = "queue") {
        unary("session.prompt", Api.sessionPrompt(sessionId, text, mode))
    }

    suspend fun cancel(sessionId: String) {
        unary("session.cancel", Api.sessionCancel(sessionId))
    }

    suspend fun models(sessionId: String): SessionModels {
        val value = unary("session.models", Api.sessionModels(sessionId))
        return runCatching { Wire.json.decodeFromJsonElement(SessionModels.serializer(), value) }
            .getOrDefault(SessionModels())
    }

    suspend fun selectModel(sessionId: String, provider: String, model: String) {
        unary("session.selectModel", Api.sessionSelectModel(sessionId, provider, model))
    }

    suspend fun rename(sessionId: String, title: String) {
        unary("session.rename", Api.sessionRename(sessionId, title))
    }

    suspend fun fork(sessionId: String, atSeq: Long? = null): String {
        val value = unary("session.fork", Api.sessionFork(sessionId, atSeq))
        return runCatching { Wire.json.decodeFromJsonElement(CreatedSession.serializer(), value).sessionId }
            .getOrElse { throw RpcBusinessException("transport", "session.fork: unexpected response") }
    }

    /**
     * Stream a phone-side file to the PC gateway (PUT /upload). The gateway
     * saves it into the session workspace's uploads/ directory and answers
     * with a relative path the agent can reference directly.
     *
     * @param size file size in bytes, or -1 when unknown (chunked transfer).
     */
    suspend fun uploadFile(
        name: String,
        size: Long,
        sessionCwd: String?,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val query = StringBuilder("/upload?name=").append(URLEncoder.encode(name, "UTF-8"))
        if (!sessionCwd.isNullOrBlank()) {
            query.append("&cwd=").append(URLEncoder.encode(sessionCwd, "UTF-8"))
        }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        sink.write(buffer, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val request = authed("$baseUrl$query").put(body).build()
        val response = try {
            upload.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw RpcBusinessException("timeout", "上传超时：请检查网络后重试")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw RpcBusinessException("unauthorized", "网关拒绝了 token（HTTP 401）")
            if (!resp.isSuccessful) throw RpcBusinessException("transport", "上传失败 HTTP ${resp.code}: ${text.take(200)}")
            runCatching { Wire.json.decodeFromString(UploadResult.serializer(), text) }
                .getOrElse { throw RpcBusinessException("transport", "invalid upload response: ${text.take(200)}") }
        }
    }
}

/** Gateway /upload answer: where the file landed and its agent-visible path. */
@Serializable
data class UploadResult(
    val name: String,
    val path: String,
    val relPath: String,
    val size: Long,
)

@kotlinx.serialization.Serializable
private data class SessionListPage(val items: List<SessionSummary> = emptyList())

@kotlinx.serialization.Serializable
private data class CreatedSession(val sessionId: String)
