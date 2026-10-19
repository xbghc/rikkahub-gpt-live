package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.GptLiveSessionManager
import me.rerere.rikkahub.service.LiveVoicePhase
import me.rerere.rikkahub.service.LiveVoiceState
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.PermissionStatus
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.util.Locale

/** The application owns the session; removing this composable never disconnects it. */
@Composable
internal fun GptLiveConversationPanel(
    vm: ChatVM,
    settings: Settings,
    conversation: Conversation,
    modifier: Modifier = Modifier,
    manager: GptLiveSessionManager = koinInject(),
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val permissions = remember {
        buildSet {
            add(PermissionRecordAudio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android can run the foreground service when notifications are declined.
                add(PermissionNotification.copy(required = false))
            }
        }
    }
    val permission = rememberPermissionState(permissions)
    PermissionManager(permission)

    val belongsToChat = state.conversationId == conversation.id
    val activeElsewhere = state.isActive && !belongsToChat
    val token = state.sessionToken
    val configured = settings.gptLive.let {
        it.enabled && it.apiKey.isNotBlank() && it.endpoint.isNotBlank() && it.model.isNotBlank() && it.voice.isNotBlank()
    }
    var showCaptions by rememberSaveable(token) { mutableStateOf(false) }

    fun start() {
        // Read current state again: a click from an older composition must not start a second session.
        val active = manager.state.value
        if (active.isActive) {
            active.conversationId?.takeIf { it != conversation.id }?.let { navigateToChatPage(navController, it) }
            return
        }
        if (!configured) {
            navController.navigate(Screen.SettingSpeech)
            return
        }
        val blocked = when {
            vm.currentChatModel.value == null -> R.string.chat_page_voice_select_model
            asr.state.value.isRecording -> R.string.gpt_live_finish_dictation
            vm.voiceSession.state.value.isActive -> R.string.gpt_live_finish_voice_mode
            vm.inputState.isEditing() -> R.string.gpt_live_finish_editing
            vm.messageQueue.value.paused && vm.messageQueue.value.messages.isNotEmpty() -> R.string.chat_page_voice_resume_queue
            vm.conversation.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            } -> R.string.chat_page_voice_pending_tools
            else -> null
        }
        if (blocked != null) {
            toaster.show(context.getString(blocked))
            return
        }
        val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permission.permissionStates[Manifest.permission.POST_NOTIFICATIONS] == PermissionStatus.NotRequested
        if (!permission.allRequiredPermissionsGranted || askNotifications) {
            permission.requestPermissions()
            toaster.show(context.getString(R.string.gpt_live_grant_permissions))
            return
        }
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        tts.stop()
        manager.start(conversation.id)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = if (state.isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.Mic01, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.gpt_live_title), style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(
                        text = when {
                            activeElsewhere -> stringResource(R.string.gpt_live_other_chat)
                            state.isActive -> liveStatus(state)
                            belongsToChat && state.phase == LiveVoicePhase.Error -> stringResource(R.string.gpt_live_session_error)
                            configured -> stringResource(R.string.gpt_live_ready)
                            else -> stringResource(R.string.gpt_live_setup_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.isActive) {
                    val seconds = state.elapsedSeconds.toLong().coerceAtLeast(0L)
                    Text(
                        text = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    )
                } else {
                    FilledTonalButton(onClick = ::start) {
                        Text(
                            stringResource(
                                when {
                                    !configured -> R.string.gpt_live_setup
                                    belongsToChat && state.phase == LiveVoicePhase.Error -> R.string.gpt_live_retry
                                    else -> R.string.gpt_live_start
                                }
                            )
                        )
                    }
                }
            }

            if (state.isActive) {
                if (activeElsewhere) {
                    Text(
                        text = stringResource(R.string.gpt_live_chat_id, state.conversationId.toString()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        state.conversationId?.let { navigateToChatPage(navController, it) }
                    }) {
                        Text(stringResource(R.string.gpt_live_open_chat))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = { token?.let { manager.toggleMuted(expectedSessionToken = it) } },
                        enabled = state.phase == LiveVoicePhase.Active && !state.microphoneChanging && token != null,
                    ) {
                        Text(stringResource(if (state.muted) R.string.gpt_live_unmute else R.string.gpt_live_mute))
                    }
                    Button(
                        onClick = { token?.let { manager.stop(expectedSessionToken = it) } },
                        enabled = state.phase != LiveVoicePhase.Closing && token != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.gpt_live_end))
                    }
                    TextButton(onClick = { showCaptions = true }) {
                        Text(stringResource(R.string.gpt_live_captions))
                    }
                }
                if (state.pendingDelegations > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.gpt_live_pending_tasks, state.pendingDelegations),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { token?.let { manager.cancelPendingDelegations(expectedSessionToken = it) } },
                            enabled = token != null && state.phase == LiveVoicePhase.Active,
                        ) {
                            Text(stringResource(R.string.gpt_live_cancel_tasks))
                        }
                    }
                }
                CaptionPreview(stringResource(R.string.gpt_live_your_voice), state.inputTranscript)
                CaptionPreview(stringResource(R.string.gpt_live_voice_reply), state.outputTranscript)
            } else if (belongsToChat) {
                SessionEndSummary(state)
                if (state.inputTranscript.isNotEmpty() || state.outputTranscript.isNotEmpty()) {
                    TextButton(onClick = { showCaptions = true }) {
                        Text(stringResource(R.string.gpt_live_captions))
                    }
                }
            }
            if (state.isActive || belongsToChat) {
                state.error?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showCaptions) {
        ModalBottomSheet(onDismissRequest = { showCaptions = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.gpt_live_captions), style = MaterialTheme.typography.titleLargeEmphasized)
                if (!belongsToChat && state.conversationId != null) {
                    Text(stringResource(R.string.gpt_live_chat_id, state.conversationId.toString()))
                }
                if (state.awaitingApproval) {
                    TextButton(onClick = {
                        showCaptions = false
                        if (activeElsewhere) {
                            state.conversationId?.let { navigateToChatPage(navController, it) }
                        }
                    }) {
                        Text(stringResource(R.string.gpt_live_review_approval))
                    }
                }
                CaptionTranscript(
                    label = stringResource(R.string.gpt_live_your_voice),
                    text = state.inputTranscript,
                    modifier = Modifier.weight(1f),
                )
                CaptionTranscript(
                    label = stringResource(R.string.gpt_live_voice_reply),
                    text = state.outputTranscript,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SessionEndSummary(state: LiveVoiceState) {
    val message = when {
        state.closeConfirmed && state.usageConfirmed -> R.string.gpt_live_ended_server_duration
        state.closeConfirmed -> R.string.gpt_live_ended_estimated_duration
        state.closeReason != null && state.closeReason != "not_started" -> R.string.gpt_live_end_unconfirmed
        else -> return
    }
    val seconds = state.elapsedSeconds.toLong().coerceAtLeast(0L)
    val duration = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    Text(
        text = stringResource(message, duration),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.closeConfirmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun liveStatus(state: LiveVoiceState): String = stringResource(
    when {
        state.phase == LiveVoicePhase.Connecting -> R.string.gpt_live_connecting
        state.phase == LiveVoicePhase.Closing -> R.string.gpt_live_closing
        state.awaitingApproval -> R.string.gpt_live_awaiting_approval
        state.microphoneChanging -> R.string.gpt_live_microphone_changing
        state.muted -> R.string.gpt_live_muted
        state.playing -> R.string.gpt_live_playing
        else -> R.string.gpt_live_listening
    }
)

@Composable
private fun CaptionPreview(label: String, text: String) {
    if (text.isNotEmpty()) {
        Text(
            text = "$label: ${text.takeLast(160)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CaptionTranscript(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmallEmphasized)
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            SelectionContainer {
                Text(
                    text = text.ifEmpty { stringResource(R.string.gpt_live_waiting_for_speech) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState(), reverseScrolling = true)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
