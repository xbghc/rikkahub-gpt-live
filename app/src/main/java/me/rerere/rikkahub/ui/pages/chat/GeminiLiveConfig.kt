package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.live.LiveSessionConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal const val GEMINI_LIVE_MODEL = "gemini-3.8-live"

internal fun Model?.isGeminiLiveModel(provider: ProviderSetting?): Boolean =
    this?.modelId == GEMINI_LIVE_MODEL && provider is ProviderSetting.Google && provider.vertexAI

/** Keep the user's proxy, TLS and DNS settings, but never record Cloud credentials or audio requests. */
internal fun googleCloudLiveHttpClient(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    followRedirects(false)
    followSslRedirects(false)
    connectTimeout(20, TimeUnit.SECONDS)
    writeTimeout(20, TimeUnit.SECONDS)
}.build()

internal fun validateGeminiLiveProvider(provider: ProviderSetting.Google) {
    require(provider.vertexAI && provider.useServiceAccount) {
        "Gemini 3.8 Live 需要标准 Google Cloud 项目的服务账号；不支持 Express API Key。"
    }
    require(provider.projectId.trim().matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]|[0-9]{6,30}"))) {
        "请填写有效的 Google Cloud 项目 ID。"
    }
    require(provider.location.trim() in setOf("us-central1", "us", "eu")) {
        "Gemini 3.8 Live 请选择 us-central1、us 或 eu 区域。"
    }
    require(
        provider.serviceAccountEmail.trim().matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+gserviceaccount\\.com"))
    ) {
        "请导入有效的 Google Cloud 服务账号 JSON。"
    }
    require(provider.privateKey.isNotBlank()) { "服务账号缺少私钥，请重新导入 JSON。" }
}

internal suspend fun createGeminiLiveConfig(
    model: Model,
    provider: ProviderSetting.Google,
    assistant: Assistant,
    conversation: Conversation,
    httpClient: OkHttpClient,
    tokenProvider: suspend (ProviderSetting.Google, OkHttpClient) -> String = ::fetchGoogleCloudLiveToken,
): LiveSessionConfig {
    require(model.isGeminiLiveModel(provider)) { "请选择 Google Cloud 的 Gemini 3.8 Live 模型。" }
    validateGeminiLiveProvider(provider)
    val location = provider.location.trim()
    val project = provider.projectId.trim()
    val host = if (location == "us" || location == "eu") {
        "aiplatform.$location.rep.googleapis.com"
    } else {
        "$location-aiplatform.googleapis.com"
    }
    fun List<CustomHeader>.connectionHeaders() = filterNot {
        it.name.lowercase() in setOf("authorization", "proxy-authorization", "x-goog-api-key", "host", "cookie")
    }
    val headers = try {
        provider.copy(customHeaders = provider.customHeaders.connectionHeaders())
            .mergeCustomHeaders((assistant.customHeaders + model.customHeaders).connectionHeaders())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("自定义请求头格式无效，请检查提供商和助手设置。")
    }
    val systemPrompt = if (assistant.allowConversationSystemPrompt) {
        conversation.customSystemPrompt?.takeIf { it.isNotBlank() } ?: assistant.systemPrompt
    } else {
        assistant.systemPrompt
    }
    return LiveSessionConfig(
        url = "wss://$host/ws/google.cloud.aiplatform.v1.LlmBidiService/BidiGenerateContent",
        apiKey = tokenProvider(provider, googleCloudLiveHttpClient(httpClient)),
        model = "projects/$project/locations/$location/publishers/google/models/$GEMINI_LIVE_MODEL",
        instructions = listOf(
            systemPrompt,
            "Have a natural voice conversation. Keep spoken replies concise and use the user's language. " +
                "No tools are connected to this voice session; do not claim to have performed searches or actions.",
        ).filter { it.isNotBlank() }.joinToString("\n\n"),
        headers = headers.names().associateWith { headers[it].orEmpty() },
        context = conversation.gptLiveContext(assistant.contextMessageLimit),
    )
}
