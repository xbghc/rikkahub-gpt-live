package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal val GEMINI_LIVE_LOCATIONS = listOf("us-central1", "us", "eu")

internal data class GeminiLiveCredentials(
    val providerId: Uuid? = null,
    val projectId: String = "",
    val location: String = "us-central1",
    val serviceAccountEmail: String = "",
    val privateKey: String = "",
) {
    val isComplete: Boolean
        get() = projectId.isNotBlank() && location in GEMINI_LIVE_LOCATIONS &&
            serviceAccountEmail.isNotBlank() && privateKey.isNotBlank()

    override fun toString(): String = "GeminiLiveCredentials(providerId=$providerId, credentials=[redacted])"
}

internal data class GeminiLiveSelection(val provider: ProviderSetting.Google, val model: Model)

/** AI Studio and Cloud Express keys are not compatible with this Cloud Live model. */
internal fun Settings.geminiLiveProviders(): List<ProviderSetting.Google> =
    providers.filterIsInstance<ProviderSetting.Google>().filter { it.vertexAI && it.useServiceAccount }

internal fun ProviderSetting.Google.toGeminiLiveCredentials() = GeminiLiveCredentials(
    providerId = id,
    projectId = projectId,
    location = location,
    serviceAccountEmail = serviceAccountEmail,
    privateKey = privateKey,
)

/** Parse only the required fields; do not retain the original JSON or trust its token endpoint. */
internal fun parseGeminiLiveServiceAccount(
    json: String,
    current: GeminiLiveCredentials = GeminiLiveCredentials(),
): GeminiLiveCredentials {
    require(json.length <= 65_536) { "服务账号 JSON 过大，请选择 Google Cloud 下载的密钥文件。" }
    val account = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: error("无法读取服务账号 JSON，请粘贴完整的密钥文件内容。")
    fun field(name: String): String = runCatching { account[name]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
    require(field("type") == "service_account") { "需要 Google Cloud 服务账号 JSON，不支持 AI Studio 或 Express API Key。" }
    val result = current.copy(
        projectId = field("project_id").trim(),
        serviceAccountEmail = field("client_email").trim(),
        privateKey = field("private_key").trim(),
    )
    require(result.projectId.isNotBlank() && result.serviceAccountEmail.isNotBlank()) {
        "服务账号 JSON 缺少 project_id 或 client_email。"
    }
    require(
        result.privateKey.startsWith("-----BEGIN PRIVATE KEY-----") &&
            result.privateKey.endsWith("-----END PRIVATE KEY-----")
    ) {
        "服务账号 JSON 中缺少有效的 private_key。"
    }
    return result
}

/** Save a dedicated voice provider without changing the current text model or an AI Studio provider. */
internal fun Settings.prepareGeminiLive(credentials: GeminiLiveCredentials): Pair<Settings, GeminiLiveSelection> {
    require(credentials.isComplete) { "请填写项目 ID，并导入 Google Cloud 服务账号 JSON。" }
    require(credentials.location in GEMINI_LIVE_LOCATIONS) { "请选择支持 Gemini Live 的区域。" }
    val provider = if (credentials.providerId == null) {
        ProviderSetting.Google(name = "Google Cloud · Gemini Live", vertexAI = true, useServiceAccount = true)
    } else {
        geminiLiveProviders().firstOrNull { it.id == credentials.providerId }
            ?: error("Google Cloud 配置已变化，请重新选择。")
    }
    val model = provider.models.firstOrNull { it.isGeminiLiveModel(provider) && it.providerOverwrite == null }
        ?: Model(modelId = "gemini-3.8-live", displayName = "Gemini 3.8 Live · 实时语音")
    val updated = provider.copy(
        enabled = true,
        vertexAI = true,
        useServiceAccount = true,
        projectId = credentials.projectId.trim(),
        location = credentials.location,
        serviceAccountEmail = credentials.serviceAccountEmail.trim(),
        privateKey = credentials.privateKey.trim(),
        models = if (provider.models.any { it.id == model.id }) provider.models else provider.models + model,
    )
    val updatedProviders = if (credentials.providerId == null) {
        providers + updated
    } else {
        providers.map { if (it.id == updated.id) updated else it }
    }
    return copy(providers = updatedProviders) to GeminiLiveSelection(updated, model)
}
