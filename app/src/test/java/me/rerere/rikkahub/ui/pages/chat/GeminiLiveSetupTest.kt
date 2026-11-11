package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveSetupTest {
    private val credentials = GeminiLiveCredentials(
        projectId = "voice-project",
        serviceAccountEmail = "voice@voice-project.iam.gserviceaccount.com",
        privateKey = "-----BEGIN PRIVATE KEY-----\nprivate-test-data\n-----END PRIVATE KEY-----",
    )

    @Test
    fun `fresh setup creates a Cloud provider and keeps AI Studio and the text model unchanged`() {
        val textModel = Model(modelId = "gemini-3.8-flash")
        val studio = ProviderSetting.Google(apiKey = "studio-only", models = listOf(textModel))
        val before = Settings(providers = listOf(studio), chatModelId = textModel.id)

        val (after, selection) = before.prepareGeminiLive(credentials)

        assertEquals(studio, after.providers.first())
        assertEquals(textModel.id, after.chatModelId)
        assertEquals(before.assistants, after.assistants)
        assertEquals(2, after.providers.size)
        assertTrue(selection.provider.vertexAI)
        assertTrue(selection.provider.useServiceAccount)
        assertEquals("", selection.provider.apiKey)
        assertEquals("gemini-3.8-live", selection.model.modelId)
        assertEquals(listOf(selection.provider), after.geminiLiveProviders())
    }

    @Test
    fun `provider choices exclude AI Studio and Express while allowing disabled Cloud accounts`() {
        val studio = ProviderSetting.Google(apiKey = "studio-only")
        val express = ProviderSetting.Google(vertexAI = true, apiKey = "express-only")
        val cloud = ProviderSetting.Google(vertexAI = true, useServiceAccount = true, enabled = false)
        assertEquals(listOf(cloud), Settings(providers = listOf(studio, express, cloud)).geminiLiveProviders())
    }

    @Test
    fun `repeat setup keeps existing model identity and unrelated provider customizations`() {
        val voice = Model(modelId = "gemini-3.8-live", displayName = "My Gemini voice", contextLength = 500)
        val text = Model(modelId = "gemini-3.8-flash")
        val provider = ProviderSetting.Google(
            vertexAI = true,
            useServiceAccount = true,
            enabled = false,
            models = listOf(text, voice),
            customHeaders = listOf(CustomHeader("X-Team", "team")),
        )
        val before = Settings(providers = listOf(provider), chatModelId = text.id)
        val (after, selection) = before.prepareGeminiLive(credentials.copy(providerId = provider.id, location = "eu"))

        assertEquals(1, after.providers.size)
        assertEquals(voice, selection.model)
        assertEquals(provider.models, selection.provider.models)
        assertEquals(provider.customHeaders, selection.provider.customHeaders)
        assertEquals("eu", selection.provider.location)
        assertEquals(text.id, after.chatModelId)
        assertTrue(selection.provider.enabled)
    }

    @Test(expected = IllegalStateException::class)
    fun `AI Studio provider cannot be converted into Cloud by passing its id`() {
        val studio = ProviderSetting.Google(apiKey = "studio-only")
        Settings(providers = listOf(studio)).prepareGeminiLive(credentials.copy(providerId = studio.id))
    }

    @Test
    fun `setup does not reuse a voice model with a provider override`() {
        val override = ProviderSetting.Google(vertexAI = true, useServiceAccount = true)
        val model = Model(modelId = "gemini-3.8-live", providerOverwrite = override)
        val provider = override.copy(models = listOf(model))
        val (_, selection) = Settings(providers = listOf(provider))
            .prepareGeminiLive(credentials.copy(providerId = provider.id))
        assertEquals(2, selection.provider.models.size)
        assertEquals(null, selection.model.providerOverwrite)
        assertTrue(model.id != selection.model.id)
    }

    @Test
    fun `service account import extracts credentials while preserving selected region and identity`() {
        val provider = ProviderSetting.Google(vertexAI = true, useServiceAccount = true, location = "eu")
        val json = serviceAccountJson()
        val imported = parseGeminiLiveServiceAccount(json, provider.toGeminiLiveCredentials())
        assertEquals(provider.id, imported.providerId)
        assertEquals("eu", imported.location)
        assertEquals(credentials.projectId, imported.projectId)
        assertEquals(credentials.serviceAccountEmail, imported.serviceAccountEmail)
        assertEquals(credentials.privateKey, imported.privateKey)
        assertTrue(imported.isComplete)
        assertFalse(imported.toString().contains("private-test-data"))
    }

    @Test
    fun `malformed JSON errors never repeat the credentials`() {
        val failure = runCatching { parseGeminiLiveServiceAccount("{private-test-data") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(failure?.message.orEmpty().contains("private-test-data"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `OAuth client JSON is not accepted as a service account`() {
        parseGeminiLiveServiceAccount("""{"type":"authorized_user","private_key":"secret"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing private key is rejected before enabling start`() {
        parseGeminiLiveServiceAccount(serviceAccountJson(privateKey = ""))
    }

    @Test
    fun `missing credentials and unsupported region cannot start`() {
        assertFalse(GeminiLiveCredentials().isComplete)
        assertFalse(credentials.copy(projectId = " ").isComplete)
        assertFalse(credentials.copy(privateKey = " ").isComplete)
        assertFalse(credentials.copy(location = "global").isComplete)
        assertTrue(credentials.copy(location = "us").isComplete)
    }

    private fun serviceAccountJson(privateKey: String = credentials.privateKey): String = buildJsonObject {
        put("type", "service_account")
        put("project_id", credentials.projectId)
        put("client_email", credentials.serviceAccountEmail)
        put("private_key", privateKey)
        put("token_uri", "https://untrusted.example/token")
    }.toString()
}
