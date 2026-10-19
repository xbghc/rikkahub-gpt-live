package me.rerere.rikkahub.ui.voice

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ai.VoiceEntryButton
import me.rerere.rikkahub.ui.pages.chat.VoiceSetupSheet
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
    fun unconfiguredVoiceEntryIsVisibleAndOpensEditableSetup() {
        val provider = gateway(apiKey = "")
        val showSetup = mutableStateOf(false)
        var startCount = 0
        compose.setContent {
            MaterialExpressiveTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.padding(24.dp)) {
                        VoiceEntryButton(onClick = { showSetup.value = true })
                    }
                    if (showSetup.value) {
                        VoiceSetupSheet(
                            providers = listOf(provider),
                            initialProviderId = null,
                            busy = false,
                            error = null,
                            onDismiss = { showSetup.value = false },
                            onStartLive = { _, _ -> startCount++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("voice_entry_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertTextContains("语音")
        saveScreenshot("entry")
        compose.onNodeWithTag("voice_entry_button").performClick()

        compose.onNodeWithText("实时语音对话").assertIsDisplayed()
        compose.onNodeWithTag("voice_api_key").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("填写 API Key 后即可开始。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice_start_live").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_start_live").performClick()
        compose.runOnIdle { assertEquals(0, startCount) }
        saveScreenshot("setup-missing-key")
    }

    @Test
    fun emptyProviderListCannotStartOrAcceptUnusableCredentials() {
        var startCount = 0
        showSetup(providers = emptyList(), onStartLive = { _, _ -> startCount++ })

        compose.onNodeWithText("暂无可用提供商").assertIsDisplayed()
        compose.onNodeWithTag("voice_provider_selector").assertIsNotEnabled()
        compose.onNodeWithTag("voice_api_key").assertIsNotEnabled()
        compose.onNodeWithTag("voice_start_live").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_start_live").performClick()
        compose.runOnIdle { assertEquals(0, startCount) }
    }

    @Test
    fun enteringKeyEnablesStartAndReturnsTrimmedKeyWithProviderId() {
        val provider = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(provider), onStartLive = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_api_key").performTextReplacement("   ")
        compose.onNodeWithTag("voice_start_live").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }

        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("  dummy-new-key  ")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_start_live").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(provider.id to "dummy-new-key"), requests) }
        saveScreenshot("setup-ready")
    }

    @Test
    fun existingKeyStartsWithoutRequiringAModelToBeAddedFirst() {
        val provider = gateway(apiKey = "dummy-saved-key")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(provider), onStartLive = { id, key -> requests += id to key })

        compose.onNodeWithText("GPT Live 1").assertIsDisplayed()
        compose.onNodeWithTag("voice_start_live").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(provider.id to "dummy-saved-key"), requests) }
    }

    @Test
    fun freshSetupDefaultsToVercelEvenWhenUnconfiguredOpenAiIsFirst() {
        val openAi = ProviderSetting.OpenAI(name = "OpenAI", apiKey = "")
        val vercel = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(openAi, vercel), onStartLive = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Vercel AI Gateway")
        compose.onNodeWithTag("voice_start_live").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("dummy-new-gateway-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_start_live").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(vercel.id to "dummy-new-gateway-key"), requests) }
    }

    @Test
    fun freshSetupKeepsVercelSelectedInsteadOfUsingAnotherProvidersSavedKey() {
        val openAi = ProviderSetting.OpenAI(name = "OpenAI", apiKey = "dummy-openai-key")
        val vercel = gateway(apiKey = "")
        val requests = mutableListOf<Pair<Uuid, String>>()
        showSetup(listOf(openAi, vercel), onStartLive = { id, key -> requests += id to key })

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Vercel AI Gateway")
        compose.onNodeWithTag("voice_start_live").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("voice_start_live").performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("voice_api_key").performScrollTo()
            .performTextReplacement("dummy-vercel-only-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_start_live").performScrollTo()
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
            onStartLive = { id, key -> requests += id to key },
        )

        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Gateway B")
        compose.onNodeWithTag("voice_start_live").performScrollTo().performClick()

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
            onStartLive = { id, key -> requests += id to key },
        )

        compose.onNodeWithTag("voice_api_key").performTextReplacement("dummy-edited-a-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_provider_selector").performScrollTo().performClick()
        compose.onNodeWithTag("voice_provider_${second.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_provider_selector").assertTextContains("Gateway B")
        compose.onNodeWithTag("voice_start_live").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(second.id to "dummy-b-key"), requests) }
    }

    @Test
    fun failedStartShowsErrorAndAllowsEditingKeyAndRetrying() {
        val provider = gateway(apiKey = "dummy-first-key")
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val requests = mutableListOf<Pair<Uuid, String>>()
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceSetupSheet(
                    providers = listOf(provider),
                    initialProviderId = provider.id,
                    busy = busy.value,
                    error = error.value,
                    onDismiss = {},
                    onStartLive = { id, key ->
                        requests += id to key
                        busy.value = true
                        error.value = null
                    },
                    onStartLegacy = {},
                )
            }
        }

        compose.onNodeWithTag("voice_start_live").performScrollTo().performClick()
        compose.onNodeWithTag("voice_busy", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("voice_start_live").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("voice_api_key").assertIsNotEnabled()
        compose.onNodeWithTag("voice_provider_selector").assertIsNotEnabled()
        compose.onNodeWithTag("voice_legacy").assertIsNotEnabled()
        compose.onNodeWithTag("voice_setup_cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests.size) }
        saveScreenshot("setup-busy")

        compose.runOnIdle {
            busy.value = false
            error.value = "连接失败，请检查 API Key 后重试。"
        }
        compose.onNodeWithTag("voice_error").performScrollTo()
            .assertIsDisplayed().assertTextContains("连接失败，请检查 API Key 后重试。")
        compose.onNodeWithTag("voice_provider_selector").assertIsEnabled()
        compose.onNodeWithTag("voice_legacy").assertIsEnabled()
        compose.onNodeWithTag("voice_setup_cancel").assertIsEnabled()
        saveScreenshot("setup-error")

        compose.onNodeWithTag("voice_api_key").performScrollTo().assertIsEnabled()
            .performTextReplacement("dummy-corrected-key")
        compose.onNodeWithTag("voice_api_key").performImeAction()
        compose.onNodeWithTag("voice_start_live").performScrollTo()
            .assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(provider.id to "dummy-first-key", provider.id to "dummy-corrected-key"),
                requests,
            )
        }
    }

    @Test
    fun legacyChoiceAndCancelUseTheirOwnCallbacksWithoutStartingLive() {
        val provider = gateway(apiKey = "")
        var legacyCount = 0
        var dismissCount = 0
        var liveCount = 0
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceSetupSheet(
                    providers = listOf(provider),
                    initialProviderId = provider.id,
                    busy = false,
                    error = null,
                    onDismiss = { dismissCount++ },
                    onStartLive = { _, _ -> liveCount++ },
                    onStartLegacy = { legacyCount++ },
                )
            }
        }

        compose.onNodeWithTag("voice_legacy").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("voice_setup_cancel").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(1, legacyCount)
            assertEquals(1, dismissCount)
            assertEquals(0, liveCount)
        }
    }

    private fun showSetup(
        providers: List<ProviderSetting.OpenAI>,
        initialProviderId: Uuid? = null,
        onStartLive: (Uuid, String) -> Unit,
    ) {
        compose.setContent {
            MaterialExpressiveTheme {
                VoiceSetupSheet(
                    providers = providers,
                    initialProviderId = initialProviderId,
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onStartLive = onStartLive,
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
