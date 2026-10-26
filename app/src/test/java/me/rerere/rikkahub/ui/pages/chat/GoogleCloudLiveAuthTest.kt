package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GoogleCloudLiveAuthTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val provider = ProviderSetting.Google(
        vertexAI = true,
        useServiceAccount = true,
        projectId = "voice-project",
        serviceAccountEmail = "voice@voice-project.iam.gserviceaccount.com",
        privateKey = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----",
    )

    @Test
    fun `service account assertion is verifiable with fixed audience scope and lifetime`() {
        val parts = googleCloudLiveAssertion(provider, 1_800_000_000).split('.')
        val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
        assertEquals(provider.serviceAccountEmail, claims.getValue("iss").jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", claims.getValue("aud").jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.getValue("scope").jsonPrimitive.content)
        assertEquals("1800003600", claims.getValue("exp").jsonPrimitive.content)
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(keys.public)
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(Base64.getUrlDecoder().decode(parts[2]))
        })
    }

    @Test
    fun `existing escaped PEM settings remain usable without exposing invalid keys`() {
        assertEquals(
            3,
            googleCloudLiveAssertion(
                provider.copy(privateKey = provider.privateKey.replace("\n", "\\n")),
                1
            ).split('.').size
        )
        val failure = runCatching {
            googleCloudLiveAssertion(
                provider.copy(privateKey = "PRIVATE_SECRET"),
                1
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure!!.message.orEmpty().contains("PRIVATE_SECRET"))
        assertEquals(null, failure.cause)
    }

    @Test
    fun `token exchange sends assertion only to Google's fixed endpoint`() = runBlocking {
        val client = client { chain ->
            assertEquals("https://oauth2.googleapis.com/token", chain.request().url.toString())
            assertEquals("POST", chain.request().method)
            val form = chain.request().body as FormBody
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.value(0))
            assertEquals(3, form.value(1).split('.').size)
            assertFalse(form.value(1).contains("PRIVATE KEY"))
            response(chain, 200, "{\"access_token\":\"valid-oauth-token\",\"expires_in\":3600}")
        }
        assertEquals("valid-oauth-token", fetchGoogleCloudLiveToken(provider, client))
    }

    @Test
    fun `OAuth errors never expose reflected assertions or private keys`() = runBlocking {
        val failure = runCatching {
            fetchGoogleCloudLiveToken(provider, client { chain ->
                response(chain, 403, "REFLECTED_SECRET " + (chain.request().body as FormBody).value(1))
            })
        }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("403"))
        // Coroutine stack-trace recovery can add a copy of the sanitized exception as its cause.
        generateSequence(failure) { it.cause }.forEach {
            assertFalse(it.message.orEmpty().contains("REFLECTED_SECRET"))
            assertFalse(it.message.orEmpty().contains("PRIVATE KEY"))
        }
    }

    @Test
    fun `malformed numeric and oversized token responses are rejected safely`() = runBlocking {
        for (body in listOf("SECRET malformed", "{\"access_token\":123}", "x".repeat(17_000))) {
            val failure = runCatching {
                fetchGoogleCloudLiveToken(provider, client { chain -> response(chain, 200, body) })
            }.exceptionOrNull()!!
            generateSequence(failure) { it.cause }.forEach {
                assertFalse(it.message.orEmpty().contains("SECRET"))
                assertFalse(it.message.orEmpty().contains(body))
            }
        }
    }

    @Test
    fun `cancelling preparation also cancels the OAuth network call`() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = client { chain ->
            entered.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(5)
            if (chain.call().isCanceled()) cancelled.countDown()
            response(chain, 200, "{\"access_token\":\"unused\"}")
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { fetchGoogleCloudLiveToken(provider, client) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
    }

    private fun client(interceptor: Interceptor): OkHttpClient = OkHttpClient.Builder().addInterceptor(
        interceptor
    ).build()

    private fun response(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}
