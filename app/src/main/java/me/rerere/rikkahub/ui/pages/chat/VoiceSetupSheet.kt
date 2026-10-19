package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.CardGroup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

@Composable
internal fun VoiceSetupSheet(
    providers: List<ProviderSetting.OpenAI>,
    initialProviderId: Uuid?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStartLive: (providerId: Uuid, apiKey: String) -> Unit,
    onStartLegacy: (() -> Unit)? = null,
) {
    var selectedProviderId by remember {
        mutableStateOf(
            providers.firstOrNull { it.id == initialProviderId }?.id
                ?: providers.firstOrNull {
                    it.apiKey.isNotBlank() &&
                        it.baseUrl.toHttpUrlOrNull()?.host == "ai-gateway.vercel.sh"
                }?.id
                ?: providers.firstOrNull {
                    it.baseUrl.toHttpUrlOrNull()?.host == "ai-gateway.vercel.sh"
                }?.id
                ?: providers.firstOrNull { it.apiKey.isNotBlank() }?.id
                ?: providers.firstOrNull()?.id
        )
    }
    val provider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()
    var apiKey by remember(provider?.id) { mutableStateOf(provider?.apiKey.orEmpty()) }
    var showProviderMenu by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { if (!busy) onDismiss() },
        sheetGesturesEnabled = !busy,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("voice_setup_sheet")
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实时语音对话", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    "选择提供商并填写 API Key，即可用 GPT Live 1 边听边说，无需另配语音识别或朗读。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CardGroup {
                formItem(headlineContent = { Text("提供商") }) {
                    Box {
                        OutlinedButton(
                            onClick = { showProviderMenu = true },
                            enabled = !busy && providers.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().testTag("voice_provider_selector"),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(provider?.name ?: "暂无可用提供商", modifier = Modifier.weight(1f))
                            Text("切换", style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(
                            expanded = showProviderMenu && !busy,
                            onDismissRequest = { showProviderMenu = false },
                        ) {
                            providers.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.name) },
                                    onClick = {
                                        selectedProviderId = candidate.id
                                        showProviderMenu = false
                                    },
                                    modifier = Modifier.testTag("voice_provider_${candidate.id}"),
                                )
                            }
                        }
                    }
                }
                item(
                    headlineContent = { Text("GPT Live 1") },
                    supportingContent = { Text("实时听说 · 自动配置模型") },
                )
                formItem(headlineContent = { Text("API Key") }) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        enabled = !busy && provider != null,
                        modifier = Modifier.fillMaxWidth().testTag("voice_api_key"),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        placeholder = { Text("填写所选提供商的 API Key") },
                        supportingText = {
                            Text(
                                if (apiKey.isBlank()) {
                                    "填写 API Key 后即可开始。"
                                } else {
                                    "开始时将保存到所选提供商。"
                                }
                            )
                        },
                    )
                }
            }

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("voice_error"),
                )
            }
            Button(
                onClick = { provider?.let { onStartLive(it.id, apiKey.trim()) } },
                enabled = !busy && provider != null && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("voice_start_live"),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).testTag("voice_busy"),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "正在准备…" else "开始实时对话")
            }
            onStartLegacy?.let { startLegacy ->
                TextButton(
                    onClick = startLegacy,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_legacy"),
                ) {
                    Text("使用当前模型的语音识别与朗读")
                }
            }
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("voice_setup_cancel"),
            ) {
                Text("取消")
            }
        }
    }
}
