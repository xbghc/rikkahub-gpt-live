package me.rerere.rikkahub.ui.voice

import android.graphics.Bitmap
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ai.VoiceEntryButton
import me.rerere.rikkahub.ui.pages.chat.GeminiLiveCredentials
import me.rerere.rikkahub.ui.pages.chat.VoiceConversationMode
import me.rerere.rikkahub.ui.pages.setting.components.VoiceConversationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

/** Exercises the production voice controls without credentials, audio devices, or network calls. */
@RunWith(AndroidJUnit4::class)
class VoiceEntryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun voiceConfigurationCanBeOpenedExplicitlyWithoutStartingVoice() {
        var starts = 0
        var configurations = 0
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceEntryButton(onClick = { starts++ }, onConfigure = { configurations++ })
            }
        }

        saveScreenshot("entry")
        compose.onNodeWithTag("voice_configure_button").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(1, configurations)
        }
        compose.onNodeWithTag("voice_entry_button").performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, configurations)
        }
    }

    @Test
    fun unconfiguredGptSettingsShowEditableKeyAndRequireItBeforeSaving() {
        var saves = 0
        showSetup(listOf(gateway(apiKey = "")), onSaveGpt = { _, _ -> saves++ })

        compose.onNodeWithText("对话模式").assertIsDisplayed()
        compose.onNodeWithTag("voice_api_key").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("填写 API Key 后即可保存。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        saveScreenshot("setup-missing-key")
    }

    @Test
    fun emptyProviderListCannotSaveUnusableGptCredentials() {
        var startCount = 0
        showSetup(providers = emptyList(), onSaveGpt = { _, _ -> startCount++ })

        compose.onNodeWithText("暂无可用提供商").assertIsDisplayed()
        compose.onNodeWithTag("voice_provider_selector").assertIsNotEnabled()
        compose.onNodeWithTag("voice_api_key").assertIsNotEnabled()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_save_configuration").performClick()
        compose.runOnIdle { assertEquals(0, startCount) }
    }

    @Test
    fun enteringKeyEnablesSaveAndReturnsTrimmedKeyWithProviderId() {
        val provider = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(provider), onSaveGpt = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_api_key").performTextReplacement("   ")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }

        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("  dummy-new-key  ")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(provider.id to "dummy-new-key"), requests) }
        saveScreenshot("setup-ready")
    }

    @Test
    fun existingKeyCanBeSavedWithoutRequiringAModelToBeAddedFirst() {
        val provider = gateway(apiKey = "dummy-saved-key")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(provider), onSaveGpt = { id, key -> requests += id to key })

        compose.onNodeWithText("GPT Live 1").assertIsDisplayed()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(provider.id to "dummy-saved-key"), requests) }
    }

    @Test
    fun freshSetupDefaultsToVercelEvenWhenUnconfiguredOpenAiIsFirst() {
        val openAi = ProviderSetting.OpenAI(name = "OpenAI", apiKey = "")
        val vercel = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(openAi, vercel), onSaveGpt = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Vercel AI Gateway")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("dummy-new-gateway-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(vercel.id to "dummy-new-gateway-key"), requests) }
    }

    @Test
    fun freshSetupKeepsVercelSelectedInsteadOfUsingAnotherProvidersSavedKey() {
        val openAi = ProviderSetting.OpenAI(name = "OpenAI", apiKey = "dummy-openai-key")
        val vercel = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(openAi, vercel), onSaveGpt = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Vercel AI Gateway")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_save_configuration").performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("dummy-vercel-only-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(vercel.id to "dummy-vercel-only-key"), requests) }
    }

    @Test
    fun initialProviderIsHonoredEvenWhenAnotherProviderAlreadyHasAKey() {
        val first = gateway(name = "Gateway A", apiKey = "dummy-a-key")
        val selected = gateway(name = "Gateway B", apiKey = "dummy-b-key")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(
            providers = listOf(first, selected),
            initialProviderId = selected.id,
            onSaveGpt = { id, key -> requests += id to key },
        )

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Gateway B")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(selected.id to "dummy-b-key"), requests) }
    }

    @Test
    fun switchingProviderUsesItsOwnKeyInsteadOfPreviousEditedCredentials() {
        val first = gateway(name = "Gateway A", apiKey = "dummy-a-key")
        val second = gateway(name = "Gateway B", apiKey = "dummy-b-key")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(
            providers = listOf(first, second),
            initialProviderId = first.id,
            onSaveGpt = { id, key -> requests += id to key },
        )

        compose.onNodeWithTag("voice_api_key").performTextReplacement("dummy-edited-a-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_provider_selector").performScrollTo().performClick()
        compose.onNodeWithTag("voice_provider_${second.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Gateway B")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(second.id to "dummy-b-key"), requests) }
    }

    @Test
    fun failedSaveShowsErrorAndAllowsEditingKeyAndRetrying() {
        val provider = gateway(apiKey = "dummy-first-key")
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val requests = mutableListOf<Pair<Uuid, String>>()
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = listOf(provider),
                    initialProviderId = provider.id,
                    busy = busy.value,
                    error = error.value,

                    onSaveGpt = { id, key ->
                        requests += id to key
                        busy.value = true
                        error.value = null
                    },
                    onSaveLegacy = {},
                    onSaveGemini = {},
                )
            }
        }

        compose.onNodeWithTag("voice_save_configuration").performScrollTo().performClick()
        compose.onNodeWithTag("voice_busy", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("voice_save_configuration").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("voice_api_key").assertIsNotEnabled()
        compose.onNodeWithTag("voice_provider_selector").assertIsNotEnabled()
        compose.onNodeWithTag("voice_mode_legacy").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests.size) }
        saveScreenshot("setup-busy")

        compose.runOnIdle {
            busy.value = false
            error.value = "保存失败，请重试。"
        }
        compose.onNodeWithTag("voice_error").performScrollTo()
            .assertIsDisplayed().assertTextContains("保存失败，请重试。")
        compose.onNodeWithTag("voice_provider_selector").assertIsEnabled()
        compose.onNodeWithTag("voice_mode_legacy").assertIsEnabled()
        saveScreenshot("setup-error")

        compose.onNodeWithTag("voice_api_key").performScrollTo().assertIsEnabled()
            .performTextReplacement("dummy-corrected-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(provider.id to "dummy-first-key", provider.id to "dummy-corrected-key"),
                requests,
            )
        }
    }

    @Test
    fun legacyChoiceIsSavedExplicitlyWithoutSubmittingLiveCredentials() {
        var legacySaves = 0
        var liveSaves = 0
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = listOf(gateway(apiKey = "")),
                    initialProviderId = null,
                    busy = false,
                    error = null,
                    onSaveGpt = { _, _ -> liveSaves++ },
                    onSaveGemini = { liveSaves++ },
                    onSaveLegacy = { legacySaves++ },
                )
            }
        }
        compose.onNodeWithTag("voice_mode_legacy").performScrollTo().performClick()
        compose.onNodeWithTag("voice_api_key").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, legacySaves) }
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, legacySaves)
            assertEquals(0, liveSaves)
        }
    }

    @Test
    fun googleCloudIsAvailableWithoutAnyPreviouslyConfiguredProviders() {
        var startCount = 0
        showGoogleSetup(onSaveGemini = { startCount++ })

        compose.onNodeWithTag(
            "voice_mode_gemini"
        ).assertIsDisplayed().assertTextContains("Gemini Live", substring = true)
        compose.onNodeWithText("Gemini 3.8 Live").assertIsDisplayed()
        compose.onNodeWithTag("voice_google_service_account_json").performScrollTo()
            .assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, startCount) }
        saveScreenshot("google-setup-missing-account")
    }

    @Test
    fun importingGoogleAccountFillsProjectAndSavesWithoutAChatModel() {
        val requests = mutableListOf<GeminiLiveCredentials>()
        showGoogleSetup(onSaveGemini = { requests += it })

        compose.onNodeWithTag("voice_google_service_account_json").performScrollTo()
            .performTextReplacement(serviceAccountJson())
        compose.onNodeWithTag("voice_google_service_account_json").performImeAction()
        compose.onNodeWithTag("voice_google_import_json").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("voice_google_project_id").performScrollTo().assertTextContains("voice-project")
        compose.onNodeWithTag("voice_google_service_account_email").performScrollTo()
            .assertTextContains("voice@voice-project.iam.gserviceaccount.com")
        compose.onNodeWithTag("voice_google_location_selector").performScrollTo().performClick()
        compose.onNodeWithTag("voice_google_location_eu").performClick()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, requests.size)
            assertEquals(null, requests.single().providerId)
            assertEquals("voice-project", requests.single().projectId)
            assertEquals("eu", requests.single().location)
            assertEquals(dummyPrivateKey, requests.single().privateKey)
        }
        saveScreenshot("google-setup-ready")
    }

    @Test
    fun invalidGoogleJsonKeepsSaveDisabledAndDoesNotEchoCredentials() {
        var starts = 0
        showGoogleSetup(onSaveGemini = { starts++ })
        compose.onNodeWithTag("voice_google_service_account_json").performScrollTo()
            .performTextReplacement("{private-secret-not-json")
        compose.onNodeWithTag("voice_google_service_account_json").performImeAction()
        compose.onNodeWithTag("voice_google_import_json").performScrollTo().performClick()
        compose.onNodeWithTag("voice_google_import_error").performScrollTo()
            .assertTextContains("无法读取服务账号 JSON", substring = true)
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test
    fun switchingGoogleProviderUsesItsOwnCredentialsAndNewConfigurationStartsEmpty() {
        val first = googleProvider("Cloud A", "project-a")
        val second = googleProvider("Cloud B", "project-b")
        val requests = mutableListOf<GeminiLiveCredentials>()
        showGoogleSetup(listOf(first, second), onSaveGemini = { requests += it })

        compose.onNodeWithTag("voice_google_project_id").performScrollTo().performTextReplacement("edited-project")
        compose.onNodeWithTag("voice_google_project_id").performImeAction()
        compose.onNodeWithTag("voice_google_provider_selector").performScrollTo().performClick()
        compose.onNodeWithTag("voice_google_provider_${second.id}").performClick()
        compose.onNodeWithTag("voice_google_project_id").performScrollTo().assertTextContains("project-b")
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(second.id, requests.single().providerId)
            assertEquals(second.projectId, requests.single().projectId)
            assertEquals(second.privateKey, requests.single().privateKey)
        }
        compose.onNodeWithTag("voice_google_provider_selector").performScrollTo().performClick()
        compose.onNodeWithTag("voice_google_provider_new").performClick()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun switchingFromGoogleToGptUsesOnlyTheGatewayKey() {
        val provider = gateway(apiKey = "gateway-only-key")
        val cloud = googleProvider("Cloud", "voice-project")
        val gptRequests = mutableListOf<Pair<Uuid, String>>()
        var googleStarts = 0
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = listOf(provider),
                    initialProviderId = provider.id,
                    busy = false,
                    error = null,

                    onSaveGpt = { id, key -> gptRequests += id to key },
                    googleProviders = listOf(cloud),
                    onSaveGemini = { googleStarts++ },
                    onSaveLegacy = {},
                    initialMode = VoiceConversationMode.GEMINI,
                )
            }
        }
        compose.onNodeWithTag("voice_mode_gpt").performClick()
        compose.onNodeWithText("GPT Live 1").assertIsDisplayed()
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(provider.id to "gateway-only-key"), gptRequests)
            assertEquals(0, googleStarts)
        }
    }

    @Test
    fun savingGoogleConfigurationDisablesDuplicateSavesAndModeChanges() {
        var saves = 0
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = emptyList(),
                    initialProviderId = null,
                    busy = true,
                    error = null,
                    initialMode = VoiceConversationMode.GEMINI,
                    onSaveGpt = { _, _ -> error("Unexpected GPT save") },
                    googleProviders = listOf(googleProvider("Cloud", "voice-project")),
                    onSaveGemini = { saves++ },
                    onSaveLegacy = {},
                )
            }
        }
        compose.onNodeWithTag("voice_save_configuration").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("voice_google_service_account_json").assertIsNotEnabled()
        compose.onNodeWithTag("voice_mode_gpt").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    private fun showGoogleSetup(
        providers: List<ProviderSetting.Google> = emptyList(),
        onSaveGemini: (GeminiLiveCredentials) -> Unit,
    ) {
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = emptyList(),
                    initialProviderId = null,
                    busy = false,
                    error = null,

                    onSaveGpt = { _, _ -> error("Unexpected GPT start") },
                    googleProviders = providers,
                    onSaveGemini = onSaveGemini,
                    onSaveLegacy = {},
                    initialMode = VoiceConversationMode.GEMINI,
                )
            }
        }
    }

    private val dummyPrivateKey = "-----BEGIN PRIVATE KEY-----\nprivate-test-data\n-----END PRIVATE KEY-----"

    private fun googleProvider(name: String, projectId: String) = ProviderSetting.Google(
        name = name,
        vertexAI = true,
        useServiceAccount = true,
        projectId = projectId,
        serviceAccountEmail = "voice@$projectId.iam.gserviceaccount.com",
        privateKey = dummyPrivateKey,
    )

    private fun serviceAccountJson(): String = buildJsonObject {
        put("type", "service_account")
        put("project_id", "voice-project")
        put("client_email", "voice@voice-project.iam.gserviceaccount.com")
        put("private_key", dummyPrivateKey)
    }.toString()

    private fun showSetup(
        providers: List<ProviderSetting.OpenAI>,
        initialProviderId: Uuid? = null,
        onSaveGpt: (Uuid, String) -> Unit,
    ) {
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceConversationSettings(
                    providers = providers,
                    initialProviderId = initialProviderId,
                    busy = false,
                    error = null,

                    onSaveGpt = onSaveGpt,
                    onSaveGemini = {},
                    onSaveLegacy = {},
                )
            }
        }
    }

    private fun gateway(
        name: String = "Vercel AI Gateway",
        apiKey: String,
    ) = ProviderSetting.OpenAI(
        name = name,
        apiKey = apiKey,
        baseUrl = "https://ai-gateway.vercel.sh/v1",
        models = emptyList(),
    )

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "voice-ui-$name.png").outputStream().use { output ->
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }
}
