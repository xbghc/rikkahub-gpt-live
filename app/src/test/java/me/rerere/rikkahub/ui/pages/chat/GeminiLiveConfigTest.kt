package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class GeminiLiveConfigTest {
    private val provider = ProviderSetting.Google(
        vertexAI = true,
        useServiceAccount = true,
        projectId = "voice-project",
        location = "us-central1",
        serviceAccountEmail = "voice@voice-project.iam.gserviceaccount.com",
        privateKey = "configured-key",
    )
    private val model = Model(modelId = GEMINI_LIVE_MODEL)
    private val assistant = Assistant(systemPrompt = "Speak gently.", allowConversationSystemPrompt = true)
    private val conversation = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
        customSystemPrompt = "Use Mandarin.",
    )

    @Test
    fun `Cloud live requires its explicit model and provider type`() {
        assertTrue(model.isGeminiLiveModel(provider))
        assertFalse(model.isGeminiLiveModel(provider.copy(vertexAI = false)))
        assertFalse(model.copy(modelId = "gemini-3.8-flash").isGeminiLiveModel(provider))
        assertFalse(model.isGeminiLiveModel(ProviderSetting.OpenAI()))
        assertFalse(null.isGeminiLiveModel(provider))
    }

    @Test
    fun `regional and multi regional Cloud endpoints use their correct hosts`() = runBlocking {
        val expected = mapOf(
            "us-central1" to "us-central1-aiplatform.googleapis.com",
            "us" to "aiplatform.us.rep.googleapis.com",
            "eu" to "aiplatform.eu.rep.googleapis.com",
        )
        for ((region, host) in expected) {
            val config = createGeminiLiveConfig(
                model,
                provider.copy(location = region),
                assistant,
                conversation,
                OkHttpClient(),
            ) { _, _ -> "oauth-token" }
            assertEquals("wss://$host/ws/google.cloud.aiplatform.v1.LlmBidiService/BidiGenerateContent", config.url)
            assertEquals(
                "projects/voice-project/locations/$region/publishers/google/models/gemini-3.8-live",
                config.model
            )
            assertEquals("oauth-token", config.apiKey)
            assertTrue(config.instructions.startsWith("Use Mandarin."))
        }
    }

    @Test
    fun `invalid Cloud settings fail before contacting authentication`() = runBlocking {
        for (invalid in listOf(
            provider.copy(vertexAI = false),
            provider.copy(useServiceAccount = false),
            provider.copy(location = "global"),
            provider.copy(projectId = "project/../../other"),
            provider.copy(serviceAccountEmail = "bad\"email"),
            provider.copy(privateKey = ""),
        )) {
            val failure = runCatching {
                createGeminiLiveConfig(model, invalid, assistant, conversation, OkHttpClient()) { _, _ ->
                    error("Must not contact OAuth")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test
    fun `configured URLs and credential headers cannot redirect Cloud credentials`() = runBlocking {
        val config = createGeminiLiveConfig(
            model,
            provider.copy(
                baseUrl = "https://untrusted.example/steal",
                customHeaders = listOf(CustomHeader("Authorization", "old\nsecret"), CustomHeader("X-Trace", "test")),
            ),
            assistant.copy(customHeaders = listOf(CustomHeader("x-goog-api-key", "studio-key"))),
            conversation,
            OkHttpClient(),
        ) { _, _ -> "oauth-token" }
        assertFalse(config.url.contains("untrusted"))
        assertEquals(mapOf("X-Trace" to "test"), config.headers)
    }

    @Test
    fun `Cloud client preserves routing but removes credential recording and redirects`() {
        val original = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .addInterceptor { error("Must not log OAuth or audio") }
            .addNetworkInterceptor { error("Must not record credentials") }
            .build()
        val cloud = googleCloudLiveHttpClient(original)
        assertEquals(Proxy.NO_PROXY, cloud.proxy)
        assertEquals(original.dns, cloud.dns)
        assertTrue(cloud.interceptors.isEmpty())
        assertTrue(cloud.networkInterceptors.isEmpty())
        assertFalse(cloud.followRedirects)
        assertFalse(cloud.followSslRedirects)
        assertEquals(1, original.interceptors.size)
    }
}
