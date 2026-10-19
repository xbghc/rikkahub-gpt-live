package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val TOKEN_BODY_LIMIT = 16_384L

/** Never trust a token_uri from imported JSON, and never send the private key to a server. */
internal fun googleCloudLiveAssertion(provider: ProviderSetting.Google, now: Long): String {
    validateGeminiLiveProvider(provider)
    val key = try {
        val pem = provider.privateKey.trim().replace("\\n", "\n").replace("\\r", "\r")
        require(pem.startsWith("-----BEGIN PRIVATE KEY-----") && pem.endsWith("-----END PRIVATE KEY-----"))
        val der = Base64.getDecoder().decode(
            pem.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----")
                .replace(Regex("\\s"), "")
        )
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (_: Exception) {
        throw IllegalArgumentException("服务账号私钥格式无效，请重新导入 Google Cloud 下载的 JSON。")
    }
    fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())
    val claims = buildJsonObject {
        put("iss", provider.serviceAccountEmail.trim())
        put("scope", "https://www.googleapis.com/auth/cloud-platform")
        put("aud", GOOGLE_TOKEN_URL)
        put("iat", now)
        put("exp", now + 3600)
    }
    val signingInput = "$header.${encode(claims.toString().toByteArray(Charsets.UTF_8))}"
    val signature = try {
        Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
    } catch (_: Exception) {
        throw IllegalArgumentException("无法使用服务账号私钥签名，请重新导入 Google Cloud 下载的 JSON。")
    }
    return "$signingInput.${encode(signature)}"
}

internal suspend fun fetchGoogleCloudLiveToken(
    provider: ProviderSetting.Google,
    httpClient: OkHttpClient,
): String = withContext(Dispatchers.IO) {
    val assertion = googleCloudLiveAssertion(provider, Instant.now().epochSecond)
    val request = Request.Builder().url(GOOGLE_TOKEN_URL).post(
        FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()
    ).build()
    // Keep cancellation attached until the bounded response has been read, not just until headers arrive.
    suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IOException("无法连接 Google Cloud 认证服务，请检查网络后重试。"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val token = runCatching {
                    response.use {
                        if (!response.isSuccessful) {
                            // Error bodies can reflect the signed assertion. Only expose the status and useful next step.
                            throw IOException(
                                "Google Cloud 认证失败（HTTP ${response.code}），请检查服务账号密钥是否有效及设备时间是否准确。"
                            )
                        }
                        val source = response.body.source()
                        if (source.request(TOKEN_BODY_LIMIT + 1)) throw IOException("Google Cloud 认证响应过大。")
                        val body = runCatching { Json.parseToJsonElement(source.readUtf8()) as? JsonObject }.getOrNull()
                        val value = (body?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        if (value.isNullOrBlank() || value.length > 8192 || value.any { it <= ' ' || it >= '\u007f' }) {
                            throw IOException("Google Cloud 未返回有效的访问令牌，请检查服务账号配置。")
                        }
                        value
                    }
                }
                if (continuation.isActive) {
                    token.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = {
                            val message = it.message?.takeIf { text -> text.startsWith("Google Cloud ") }
                                ?: "Google Cloud 认证响应读取失败，请稍后重试。"
                            continuation.resumeWithException(IOException(message))
                        },
                    )
                }
            }
        })
    }
}
