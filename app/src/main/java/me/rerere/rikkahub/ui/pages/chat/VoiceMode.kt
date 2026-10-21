package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.live.GeminiLiveConversation
import me.rerere.live.GptLiveConversation
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private sealed interface VoiceStartRequest {
    data class Live(val providerId: Uuid, val apiKey: String) : VoiceStartRequest
    data class Gemini(val credentials: GeminiLiveCredentials) : VoiceStartRequest
    data object Legacy : VoiceStartRequest
}

/** Lives above adaptive drawer branches, so resizing does not recreate the voice session. */
@Composable
fun rememberVoiceModeStarter(vm: ChatVM, settings: Settings): () -> Unit {
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val googleClient = remember(client) { googleCloudLiveHttpClient(client) }
    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val voice = vm.voiceSession
    val state by voice.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSetup by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preparation by remember { mutableStateOf<Job?>(null) }
    var awaitingPermission by remember { mutableStateOf<VoiceStartRequest?>(null) }
    var lastProviderId by remember { mutableStateOf<Uuid?>(null) }
    var lastGoogleProviderId by remember { mutableStateOf<Uuid?>(null) }
    var lastGoogleCloud by remember { mutableStateOf<Boolean?>(null) }

    fun blockedReason(live: Boolean): String? = when {
        asr.state.value.isRecording -> context.getString(R.string.chat_page_voice_finish_dictation)
        live && (vm.conversationJob.value != null || vm.messageQueue.value.messages.isNotEmpty()) ->
            "请先等待当前回复和待发送消息处理完成，再开始实时语音对话。"
        vm.messageQueue.value.paused && vm.messageQueue.value.messages.isNotEmpty() ->
            context.getString(R.string.chat_page_voice_resume_queue)
        vm.conversation.value.currentMessages.any { message ->
            message.parts.any { it is UIMessagePart.Tool && it.isPending }
        } -> context.getString(R.string.chat_page_voice_pending_tools)
        else -> null
    }

    fun execute(request: VoiceStartRequest) {
        if (voice.state.value.isActive) {
            setupError = "请先结束正在进行的语音对话。"
            return
        }
        blockedReason(request != VoiceStartRequest.Legacy)?.let {
            setupError = it
            return
        }
        when (request) {
            is VoiceStartRequest.Live -> {
                busy = true
                setupError = null
                preparation = scope.launch {
                    try {
                        val selected = vm.prepareLiveVoice(request.providerId, request.apiKey)
                        // Voice has its own model; entering voice must not replace the text chat model.
                        val conversation = vm.conversation.value
                        val assistant = vm.settings.value.getAssistantOf(conversation)
                        val backendModel = vm.currentChatModel.value
                        val backendProvider = backendModel?.findProvider(vm.settings.value.providers)
                        val backendEnabled = backendModel != null && backendProvider != null &&
                            !backendModel.isGptLiveModel(backendProvider) &&
                            !backendModel.isGeminiLiveModel(backendProvider)
                        val config = createGptLiveConfig(
                            selected.model,
                            selected.provider,
                            assistant,
                            conversation,
                            KeyRoulette.lru(context),
                            backendEnabled = backendEnabled,
                        )
                        blockedReason(live = true)?.let { error(it) }
                        vm.startLiveVoice(
                            model = selected.model,
                            createSession = { GptLiveConversation(context, client, config) },
                            stopSpeaking = tts::stop,
                            backendEnabled = backendEnabled,
                        )
                        lastProviderId = selected.provider.id
                        lastGoogleCloud = false
                        showSetup = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setupError = e.message ?: "实时语音启动失败，请重试。"
                    } finally {
                        busy = false
                    }
                }
            }
            is VoiceStartRequest.Gemini -> {
                busy = true
                setupError = null
                preparation = scope.launch {
                    try {
                        val selected = vm.prepareGeminiLiveVoice(request.credentials)
                        // Keep the saved ID even if authentication fails, so retry edits the same configuration.
                        lastGoogleProviderId = selected.provider.id
                        lastGoogleCloud = true
                        val conversation = vm.conversation.value
                        val assistant = vm.settings.value.getAssistantOf(conversation)
                        val config = createGeminiLiveConfig(
                            selected.model,
                            selected.provider,
                            assistant,
                            conversation,
                            googleClient,
                        )
                        blockedReason(live = true)?.let { error(it) }
                        vm.startLiveVoice(
                            model = selected.model,
                            createSession = { GeminiLiveConversation(context, googleClient, config) },
                            stopSpeaking = tts::stop,
                            assistantLabel = "Gemini Live",
                        )
                        showSetup = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setupError = e.message ?: "Google Cloud 实时语音启动失败，请重试。"
                    } finally {
                        busy = false
                    }
                }
            }
            VoiceStartRequest.Legacy -> {
                val currentSettings = vm.settings.value
                val provider = currentSettings.getSelectedASRProvider()
                if (provider?.supportsServerVadVoiceMode != true || vm.currentChatModel.value == null) {
                    setupError = "请先配置当前聊天模型和支持语音对话的语音识别服务。"
                    return
                }
                voice.start(
                    createAsr = { createVoiceAsr(context, client, provider) },
                    speak = if (tts.isAvailable.value) {
                        { reply ->
                            var text = reply
                            if (currentSettings.displaySetting.ttsOnlyReadQuoted) {
                                text = text.extractQuotedContentAsText() ?: text
                            }
                            if (currentSettings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                text = text.removeBracketedContent() ?: text
                            }
                            // TTS removes markup once; keep escaped literal tags intact.
                            if (text.stripMarkdown().isNotBlank()) {
                                tts.speak(text)
                                combine(tts.isSpeaking, tts.error) { speaking, error ->
                                    check(error == null) { error.orEmpty() }
                                    !speaking
                                }.first { it }
                            }
                        }
                    } else {
                        null
                    },
                    stopSpeaking = tts::stop,
                    listenWhileReplying = currentSettings.displaySetting.voiceModeListenWhileReplying,
                )
                showSetup = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = awaitingPermission
        awaitingPermission = null
        if (showSetup && request != null) {
            if (granted) {
                execute(request)
            } else {
                setupError = "需要麦克风权限才能开始语音对话。若已拒绝，请在系统的应用权限设置中允许麦克风。"
            }
        }
    }

    fun requestStart(request: VoiceStartRequest) {
        if (busy || voice.state.value.isActive || awaitingPermission != null) return
        setupError = null
        blockedReason(request != VoiceStartRequest.Legacy)?.let {
            setupError = it
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            execute(request)
        } else {
            awaitingPermission = request
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(voice, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                awaitingPermission = null
                preparation?.cancel()
                voice.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            awaitingPermission = null
            preparation?.cancel()
            voice.stop()
        }
    }

    if (showSetup) {
        val chatModel by vm.currentChatModel.collectAsStateWithLifecycle()
        val selectedProvider = chatModel?.findProvider(settings.providers)
        val legacyAvailable = settings.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
            chatModel != null && !chatModel.isGptLiveModel(selectedProvider) &&
            !chatModel.isGeminiLiveModel(selectedProvider)
        VoiceSetupSheet(
            providers = settings.gptLiveProviders(),
            initialProviderId = lastProviderId ?: selectedProvider?.id.takeIf {
                chatModel.isGptLiveModel(selectedProvider)
            },
            busy = busy || awaitingPermission != null,
            error = setupError,
            onDismiss = {
                showSetup = false
                awaitingPermission = null
            },
            onStartLive = { providerId, key -> requestStart(VoiceStartRequest.Live(providerId, key)) },
            onStartLegacy = if (legacyAvailable) ({ requestStart(VoiceStartRequest.Legacy) }) else null,
            googleProviders = settings.geminiLiveProviders(),
            initialGoogleProviderId = lastGoogleProviderId ?: selectedProvider?.id.takeIf {
                chatModel.isGeminiLiveModel(selectedProvider)
            },
            initialGoogleCloud = lastGoogleCloud ?: !chatModel.isGptLiveModel(selectedProvider),
            onStartGemini = { requestStart(VoiceStartRequest.Gemini(it)) },
            onCancelPreparation = {
                awaitingPermission = null
                preparation?.cancel()
                showSetup = false
            },
            backendModelName = chatModel?.takeIf {
                selectedProvider != null && !it.isGptLiveModel(selectedProvider) &&
                    !it.isGeminiLiveModel(selectedProvider)
            }?.displayName,
        )
    }

    if (state.isActive) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
    return {
        if (!state.isActive) {
            setupError = null
            showSetup = true
        } else {
            toaster.show("语音对话已在进行中。")
        }
    }
}

private fun createVoiceAsr(context: Context, client: OkHttpClient, provider: ASRProviderSetting): ASRController {
    val delegate = when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            OpenAIRealtimeASRController(context, client, provider)
        }
        is ASRProviderSetting.DashScope -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            DashScopeASRController(context, client, provider)
        }
        is ASRProviderSetting.Volcengine -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            VolcengineASRController(context, client, provider)
        }
        else -> error(context.getString(R.string.chat_page_voice_no_endpointing))
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()
    return object : ASRController by delegate {
        override fun start(onTranscriptChange: (String) -> Unit) {
            check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Unable to acquire audio focus for the microphone. Try again later."
            }
            delegate.start(onTranscriptChange)
        }

        override fun dispose() {
            try {
                delegate.dispose()
            } finally {
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
    }
}
