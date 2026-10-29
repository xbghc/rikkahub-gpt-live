package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class GooglePriorityInferenceTest {
    private val requestBody = json.parseToJsonElement(
        """{"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}"""
    ).jsonObject
    private val params = TextGenerationParams(model = Model(modelId = "gemini-2.5-flash"))
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `API key requests use priority for both generation methods`() = runTest {
        val setting = ProviderSetting.Google(vertexAI = true, priorityInference = true, apiKey = "test-key")

        for (stream in listOf(false, true)) {
            val request = provider.buildGenerationRequest(setting, requestBody, params, stream)

            assertPriorityHeaders(request)
            assertNull(request.header("X-Vertex-AI-LLM-Request-Type"))
            assertEquals("aiplatform.googleapis.com", request.url.host)
            assertEquals(
                "/v1/publishers/google/models/gemini-2.5-flash:" +
                    if (stream) "streamGenerateContent" else "generateContent",
                request.url.encodedPath,
            )
            assertEquals(if (stream) "sse" else null, request.url.queryParameter("alt"))
            assertEquals("test-key", request.url.queryParameter("key"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("x-goog-api-key"))
            assertEquals("POST", request.method)
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            assertEquals(requestBody, json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()))
        }
    }

    @Test
    fun `service account requests keep bearer authentication and global endpoint`() = runTest {
        val tokenRequests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
                tokenRequests += request
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"test-token","expires_in":3600}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val serviceAccountProvider = GoogleProvider(client)
        val privateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val setting = ProviderSetting.Google(
            vertexAI = true,
            priorityInference = true,
            useServiceAccount = true,
            projectId = "test-project",
            location = "global",
            serviceAccountEmail = "test@test-project.iam.gserviceaccount.com",
            privateKey = Base64.getEncoder().encodeToString(privateKey.encoded),
        )

        for (stream in listOf(false, true)) {
            val request = serviceAccountProvider.buildGenerationRequest(setting, requestBody, params, stream)

            assertPriorityHeaders(request)
            assertNull(request.header("X-Vertex-AI-LLM-Request-Type"))
            assertEquals(
                "/v1/projects/test-project/locations/global/publishers/google/models/gemini-2.5-flash:" +
                    if (stream) "streamGenerateContent" else "generateContent",
                request.url.encodedPath,
            )
            assertEquals("Bearer test-token", request.header("Authorization"))
            assertNull(request.url.queryParameter("key"))
            assertEquals(if (stream) "sse" else null, request.url.queryParameter("alt"))
        }
        assertEquals(1, tokenRequests.size)
        assertNoPriorityHeaders(tokenRequests.single())

        val standard = serviceAccountProvider.buildGenerationRequest(
            setting.copy(priorityInference = false, location = "us-central1"),
            requestBody,
            params,
            stream = false,
        )
        assertNoPriorityHeaders(standard)
        assertTrue(standard.url.encodedPath.contains("/locations/us-central1/"))
        assertEquals("Bearer test-token", standard.header("Authorization"))
    }

    @Test
    fun `priority stays off for existing Vertex settings and non Vertex requests`() = runTest {
        val settings = listOf(
            ProviderSetting.Google(vertexAI = true, apiKey = "test-key"),
            ProviderSetting.Google(apiKey = "test-key", priorityInference = true, useServiceAccount = true),
        )

        for (setting in settings) {
            for (stream in listOf(false, true)) {
                val request = provider.buildGenerationRequest(setting, requestBody, params, stream)

                assertNoPriorityHeaders(request)
                if (!setting.vertexAI) {
                    assertEquals("generativelanguage.googleapis.com", request.url.host)
                    assertTrue(request.url.encodedPath.startsWith("/v1beta/models/"))
                    assertEquals("test-key", request.header("x-goog-api-key"))
                    assertNull(request.url.queryParameter("key"))
                }
            }
        }
    }

    @Test
    fun `priority replaces conflicting headers without losing other custom or session headers`() = runTest {
        val setting = ProviderSetting.Google(
            vertexAI = true,
            priorityInference = true,
            apiKey = "test-key",
            customHeaders = listOf(
                CustomHeader("x-vertex-ai-llm-shared-request-type", "flex"),
                CustomHeader("X-Custom", "provider"),
                CustomHeader("X-Provider", "kept"),
            ),
        )
        val customParams = params.copy(
            sessionId = "test-session",
            customHeaders = listOf(
                CustomHeader("x-vertex-ai-llm-request-type", "dedicated"),
                CustomHeader("x-custom", "request"),
            ),
        )

        val request = provider.buildGenerationRequest(setting, requestBody, customParams, stream = true)

        assertPriorityHeaders(request)
        assertEquals("dedicated", request.header("X-Vertex-AI-LLM-Request-Type"))
        assertEquals(listOf("request"), request.headers("X-Custom"))
        assertEquals("kept", request.header("X-Provider"))
        assertEquals("test-session", request.header("X-Session-ID"))

        val disabled = provider.buildGenerationRequest(
            setting.copy(priorityInference = false),
            requestBody,
            customParams,
            stream = true,
        )
        assertEquals("flex", disabled.header("X-Vertex-AI-LLM-Shared-Request-Type"))
        assertEquals("dedicated", disabled.header("X-Vertex-AI-LLM-Request-Type"))
    }

    @Test
    fun `regional priority requests fail before fetching a token without changing location`() = runTest {
        val setting = ProviderSetting.Google(
            vertexAI = true,
            priorityInference = true,
            useServiceAccount = true,
            location = "us-central1",
        )

        for (stream in listOf(false, true)) {
            val error = runCatching {
                provider.buildGenerationRequest(setting, requestBody, params, stream)
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains("Set the provider location to global"))
            assertEquals("us-central1", setting.location)
        }
    }

    @Test
    fun `old settings default to standard and priority survives serialization and copying`() {
        val legacy = json.decodeFromString<ProviderSetting>("""{"type":"google","vertexAI":true}""")
            as ProviderSetting.Google
        assertFalse(legacy.priorityInference)

        val setting = legacy.copy(priorityInference = true)
        val restored = json.decodeFromString<ProviderSetting>(json.encodeToString<ProviderSetting>(setting))
            as ProviderSetting.Google

        assertEquals(setting, restored)
        assertTrue((restored.copyProvider(name = "Copied") as ProviderSetting.Google).priorityInference)
    }

    private fun assertPriorityHeaders(request: Request) {
        assertEquals(listOf("priority"), request.headers("X-Vertex-AI-LLM-Shared-Request-Type"))
    }

    private fun assertNoPriorityHeaders(request: Request) {
        assertNull(request.header("X-Vertex-AI-LLM-Request-Type"))
        assertNull(request.header("X-Vertex-AI-LLM-Shared-Request-Type"))
    }
}
