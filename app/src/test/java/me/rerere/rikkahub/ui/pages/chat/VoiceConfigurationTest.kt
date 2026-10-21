package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceConfigurationTest {
    private val cloud = ProviderSetting.Google(
        vertexAI = true,
        useServiceAccount = true,
        projectId = "voice-project",
        serviceAccountEmail = "voice@voice-project.iam.gserviceaccount.com",
        privateKey = "dummy-private-key",
        models = listOf(Model(modelId = "gemini-3.8-live")),
    )
    private val gateway = ProviderSetting.OpenAI(
        baseUrl = "https://ai-gateway.vercel.sh/v1",
        apiKey = "dummy-key",
        models = listOf(Model(modelId = "openai/gpt-live-1")),
    )

    @Test
    fun `saved mode is honored when both Live services are configured`() {
        val settings = Settings(providers = listOf(gateway, cloud))
        assertEquals(VoiceConversationMode.GEMINI, settings.voiceConversationMode("gemini"))
        assertEquals(VoiceConversationMode.LEGACY, settings.voiceConversationMode("legacy"))
        assertEquals(VoiceConversationMode.GPT, settings.voiceConversationMode("gpt"))
        assertEquals(cloud, (settings.configuredVoiceRequest(VoiceConversationMode.GEMINI) as VoiceStartRequest.Gemini).selection.provider)
        assertEquals(gateway, (settings.configuredVoiceRequest(VoiceConversationMode.GPT) as VoiceStartRequest.Live).selection.provider)
        assertEquals(VoiceStartRequest.Legacy, settings.configuredVoiceRequest(VoiceConversationMode.LEGACY))
    }

    @Test
    fun `existing Gemini setup without a provider preference still starts after upgrade`() {
        val settings = Settings(providers = listOf(cloud))
        assertEquals(VoiceConversationMode.GEMINI, settings.voiceConversationMode(null))
        assertNotNull(settings.configuredVoiceRequest(VoiceConversationMode.GEMINI))
    }

    @Test
    fun `disabled incomplete overridden or deleted Gemini configuration requires settings`() {
        val invalid = listOf(
            cloud.copy(enabled = false),
            cloud.copy(projectId = ""),
            cloud.copy(privateKey = ""),
            cloud.copy(location = "global"),
            cloud.copy(models = emptyList()),
            cloud.copy(models = listOf(cloud.models.single().copy(providerOverwrite = cloud))),
        )
        invalid.forEach { provider ->
            assertNull(Settings(providers = listOf(provider)).configuredVoiceRequest(VoiceConversationMode.GEMINI))
        }
        assertNull(Settings(init = true, providers = listOf(cloud)).configuredGeminiLiveProvider())
        // Never silently switch to a different account after the saved account has been removed.
        assertNull(Settings(providers = listOf(cloud)).configuredVoiceRequest(VoiceConversationMode.GEMINI, preferredGeminiProviderId = Uuid.random()))
    }

    @Test
    fun `saved provider identity takes priority and startup preserves the text model`() {
        val selected = cloud.copy(id = Uuid.random(), projectId = "selected-project")
        val textModelId = Uuid.random()
        val settings = Settings(providers = listOf(cloud, selected, gateway), chatModelId = textModelId)
        val request = settings.configuredVoiceRequest(VoiceConversationMode.GEMINI, preferredGeminiProviderId = selected.id)
        assertEquals(selected, (request as VoiceStartRequest.Gemini).selection.provider)
        assertEquals(textModelId, settings.chatModelId)
        assertNull(settings.configuredVoiceRequest(VoiceConversationMode.GPT, preferredGptProviderId = Uuid.random()))
    }
}
