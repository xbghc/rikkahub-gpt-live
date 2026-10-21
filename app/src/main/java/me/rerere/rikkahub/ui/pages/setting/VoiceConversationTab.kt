package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.pages.chat.GEMINI_LIVE_PROVIDER_PREFERENCE
import me.rerere.rikkahub.ui.pages.chat.GPT_LIVE_PROVIDER_PREFERENCE
import me.rerere.rikkahub.ui.pages.chat.VOICE_MODE_PREFERENCE
import me.rerere.rikkahub.ui.pages.chat.VoiceConversationMode
import me.rerere.rikkahub.ui.pages.chat.geminiLiveProviders
import me.rerere.rikkahub.ui.pages.chat.gptLiveProviders
import me.rerere.rikkahub.ui.pages.chat.voiceConversationMode
import me.rerere.rikkahub.ui.pages.setting.components.VoiceConversationSettings
import kotlin.uuid.Uuid

@Composable
internal fun VoiceConversationTab(settings: Settings, vm: SettingVM) {
    if (settings.init) {
        Text("正在加载语音配置…")
        return
    }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val savedMode by rememberSharedPreferenceString(VOICE_MODE_PREFERENCE)
    val gptProviderId by rememberSharedPreferenceString(GPT_LIVE_PROVIDER_PREFERENCE)
    val geminiProviderId by rememberSharedPreferenceString(GEMINI_LIVE_PROVIDER_PREFERENCE)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(mode: VoiceConversationMode, persist: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                persist()
                context.writeStringPreference(VOICE_MODE_PREFERENCE, mode.preferenceValue)
                toaster.show("语音配置已保存，回到聊天页点击“语音”即可开始。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "保存失败，请重试。"
            } finally {
                busy = false
            }
        }
    }

    VoiceConversationSettings(
        providers = settings.gptLiveProviders(),
        initialProviderId = gptProviderId?.let(Uuid::parseOrNull),
        googleProviders = settings.geminiLiveProviders(),
        initialGoogleProviderId = geminiProviderId?.let(Uuid::parseOrNull),
        initialMode = settings.voiceConversationMode(savedMode),
        busy = busy,
        error = error,
        onSaveGpt = { id, key ->
            save(VoiceConversationMode.GPT) {
                val selection = vm.saveGptLive(id, key)
                context.writeStringPreference(GPT_LIVE_PROVIDER_PREFERENCE, selection.provider.id.toString())
            }
        },
        onSaveGemini = { credentials ->
            save(VoiceConversationMode.GEMINI) {
                val selection = vm.saveGeminiLive(credentials)
                context.writeStringPreference(GEMINI_LIVE_PROVIDER_PREFERENCE, selection.provider.id.toString())
            }
        },
        onSaveLegacy = { save(VoiceConversationMode.LEGACY) {} },
    )
}
