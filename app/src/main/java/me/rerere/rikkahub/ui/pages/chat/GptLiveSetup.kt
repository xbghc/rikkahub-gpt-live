package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

internal data class GptLiveSelection(val provider: ProviderSetting.OpenAI, val model: Model)

/** Include disabled providers so a fresh install and an upgraded configuration can both get started. */
internal fun Settings.gptLiveProviders(): List<ProviderSetting.OpenAI> =
    providers.filterIsInstance<ProviderSetting.OpenAI>().filter { provider ->
        provider.baseUrl.toHttpUrlOrNull()?.host in setOf("ai-gateway.vercel.sh", "api.openai.com") ||
            provider.models.any { it.isGptLiveModel(provider) }
    }

/** A saved voice model distinguishes completed setup from credentials used only for text chat. */
internal fun Settings.configuredGptLiveProvider(preferredProviderId: Uuid? = null): ProviderSetting.OpenAI? {
    if (init) return null
    val configured = gptLiveProviders().filter { provider ->
        provider.enabled && provider.apiKey.isNotBlank() && provider.baseUrl.toHttpUrlOrNull()?.isHttps == true &&
            provider.models.any { it.isGptLiveModel(provider) && it.providerOverwrite == null }
    }
    if (preferredProviderId != null) return configured.firstOrNull { it.id == preferredProviderId }
    return configured.firstOrNull { it.baseUrl.toHttpUrlOrNull()?.host == "ai-gateway.vercel.sh" }
        ?: configured.firstOrNull()
}

/** Add the voice model only after an explicit setup action, preserving all existing provider settings. */
internal fun Settings.prepareGptLive(providerId: Uuid, apiKey: String): Pair<Settings, GptLiveSelection> {
    require(apiKey.isNotBlank()) { "请填写 API Key。" }
    val provider = gptLiveProviders().firstOrNull { it.id == providerId }
        ?: error("提供商配置已变化，请重新选择。")
    val url = requireNotNull(provider.baseUrl.toHttpUrlOrNull()) { "请检查提供商地址。" }
    require(url.isHttps) { "实时语音需要 HTTPS 提供商地址。" }
    // This setup edits the chosen provider's key; don't silently route through a model-level override.
    val model = provider.models.firstOrNull { it.isGptLiveModel(provider) && it.providerOverwrite == null }
        ?: Model(
            modelId = if (url.host == "ai-gateway.vercel.sh") "openai/gpt-live-1" else "gpt-live-1",
            displayName = "GPT Live 1 · 实时语音",
        )
    val updated = provider.copy(
        enabled = true,
        apiKey = apiKey.trim(),
        models = if (provider.models.any { it.id == model.id }) provider.models else provider.models + model,
    )
    return copy(providers = providers.map { if (it.id == providerId) updated else it }) to
        GptLiveSelection(updated, model)
}
