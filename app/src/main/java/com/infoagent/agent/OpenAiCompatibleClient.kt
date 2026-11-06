package com.infoagent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenAI 兼容接口调用参数（§4 ProviderConfig） */
data class ApiConfig(
    val baseUrl: String,
    val endpoint: String = "/chat/completions",
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.3,
    /** 简报输出（怎么办/要点/时间线/推荐）用 2048 会被截断，默认给 3072 */
    val maxTokens: Int = 3072,
)

data class ChatMessage(val role: String, val content: String)

data class ChatResult(
    val content: String,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /**
     * 服务端给的结束原因：stop=正常结束，length=**被 max_tokens 截断**。
     *
     * 为什么要单独记录：截断和「模型不肯按 JSON 回答」在用户看来都是「解析失败」，
     * 但处理方式完全不同——前者加长重试就能救回来，后者重试一百次也一样。
     */
    val finishReason: String? = null,
)

interface AgentClient {
    /** 非流式补全；[onDelta] 不为 null 时尝试流式增量回调（失败则整体回退非流式） */
    suspend fun complete(config: ApiConfig, messages: List<ChatMessage>, onDelta: ((String) -> Unit)? = null): ChatResult
    fun cancel()
}

/** §6 URL 校验：强制 HTTPS；HTTP 仅允许 localhost/局域网字面量 */
object EndpointSecurity {

    private val privateHost = Regex("""^(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)$""")

    fun validate(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "接口地址不能为空"
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return "接口地址格式不正确"
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return "接口地址缺少域名"
        if (scheme == "https") return null
        if (scheme == "http") {
            return if (privateHost.matches(host)) null
            else "出于安全，仅允许 https 或局域网 http 地址"
        }
        return "接口地址必须以 http(s) 开头"
    }
}

/**
 * OpenAI 兼容客户端（§8.1）：SSE 流式；event-only 空 data 帧丢弃（D14）；
 * 60s 超时；IOException 重试 1 次。
 */
/** 带状态码的 HTTP 失败，用于区分「可重试」与「不该重试」 */
class ApiHttpException(val code: Int, message: String) : java.io.IOException(message)

/**
 * OpenAI 兼容客户端（§8.1）：SSE 流式；event-only 空 data 帧丢弃（D14）；
 * 60s 超时；仅对 429/5xx/网络异常重试一次并退避。
 */
class OpenAiCompatibleClient(
    private val http: OkHttpClient = SHARED_HTTP,
) : AgentClient {

    companion object {
        /** C4：进程内共享连接池，避免每个 Worker 新建 OkHttpClient */
        val SHARED_HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /** 取消交给协程取消（await 已注册 invokeOnCancellation），不再用共享的可变字段 */
    override fun cancel() = Unit

    override suspend fun complete(
        config: ApiConfig,
        messages: List<ChatMessage>,
        onDelta: ((String) -> Unit)?,
    ): ChatResult {
        if (onDelta != null) {
            var received = false
            try {
                return stream(config, messages) { piece ->
                    received = true
                    onDelta(piece)
                } // 流式成功直接返回
            } catch (e: IOException) {
                // C5：已经吐字后失败不再回退非流式，否则半截流 + 完整结果会叠加
                if (received) throw e
            }
        }
        return nonStreaming(config, messages)
    }

    private fun endpointUrl(config: ApiConfig): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val path = config.endpoint.trim()
        // C7：baseUrl 已经写到 /chat/completions 时不再重复拼接
        if (base.endsWith("/chat/completions") || base.endsWith("/v1/chat/completions")) return base
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    private fun requestBody(
        config: ApiConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        jsonMode: Boolean = true,
        maxTokensKey: String = "max_tokens",
    ): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", arr)
            .put("temperature", config.temperature)
            .put(maxTokensKey, config.maxTokens)
            .put("stream", stream)
        // C7：部分兼容端点不认 response_format，400 时由上层的变体重试兜底
        if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
        if (stream) body.put("stream_options", JSONObject().put("include_usage", true))
        return body.toString()
    }

    private fun buildRequest(config: ApiConfig, body: String): Request =
        Request.Builder()
            .url(endpointUrl(config))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    private suspend fun nonStreaming(config: ApiConfig, messages: List<ChatMessage>): ChatResult {
        // C7 兼容链：标准 → 去掉 response_format → 换 max_completion_tokens；仅 400 时降级
        val variants = listOf(
            true to "max_tokens",
            false to "max_tokens",
            false to "max_completion_tokens",
        )
        var last: java.io.IOException? = null
        variants.forEachIndexed { index, (jsonMode, maxKey) ->
            val body = requestBody(config, messages, stream = false, jsonMode = jsonMode, maxTokensKey = maxKey)
            try {
                return executeWithRetry(config, body)
            } catch (e: ApiHttpException) {
                last = e
                // A6：上下文超限也是 400，但重发同样的超长 prompt 三次毫无意义
                val body = e.message.orEmpty().lowercase()
                if (e.code == 400 && listOf("context", "too long", "max_token", "token limit", "上下文").any { body.contains(it) }) {
                    throw java.io.IOException("素材过长：超出模型上下文限制，请缩短素材或换更大上下文模型")
                }
                if (e.code != 400 || index == variants.lastIndex) throw e
            }
        }
        throw last ?: java.io.IOException("请求失败")
    }

    /** C6：只对 429/5xx/网络异常重试一次，并做 400ms 退避；401/403 之类立即失败 */
    private suspend fun executeWithRetry(config: ApiConfig, body: String): ChatResult {
        var attempt = 0
        while (true) {
            try {
                return executeOnce(config, body)
            } catch (e: ApiHttpException) {
                val retriable = e.code == 429 || e.code >= 500
                if (!retriable || attempt >= 1) throw e
            } catch (e: java.io.IOException) {
                if (attempt >= 1) throw e
            }
            attempt++
            kotlinx.coroutines.delay(400L * attempt)
        }
    }

    private suspend fun executeOnce(config: ApiConfig, body: String): ChatResult {
        val call = http.newCall(buildRequest(config, body))
        val response = call.await()
        response.use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, "HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            val text = r.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrElse { throw IOException("响应非 JSON") }
            val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
            val usage = obj.optJSONObject("usage")
            return ChatResult(
                content = content,
                tokensIn = usage?.optLong("prompt_tokens") ?: 0,
                tokensOut = usage?.optLong("completion_tokens") ?: 0,
                finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private suspend fun stream(config: ApiConfig, messages: List<ChatMessage>, onDelta: (String) -> Unit): ChatResult {
        val body = requestBody(config, messages, stream = true)
        val call = http.newCall(buildRequest(config, body))
        val response = call.await()
        response.use { r ->
            if (!r.isSuccessful) throw ApiHttpException(r.code, "HTTP ${r.code}")
            val source = r.body?.source() ?: throw IOException("空响应体")
            val buffer = StringBuilder()
            var tokensOut = 0L
            var tokensIn = 0L
            var finish: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue // 丢弃 event-only/注释帧（D14）
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                chunk.optJSONObject("usage")?.let { u ->
                    tokensIn = u.optLong("prompt_tokens", tokensIn)
                    tokensOut = u.optLong("completion_tokens", tokensOut)
                }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                choice?.optString("finish_reason")?.takeIf { it.isNotBlank() }?.let { finish = it }
                val delta = choice?.optJSONObject("delta")
                val piece = delta?.optString("content").orEmpty()
                if (piece.isNotEmpty()) {
                    buffer.append(piece)
                    onDelta(piece)
                }
            }
            return ChatResult(buffer.toString(), tokensIn, tokensOut, finish)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}