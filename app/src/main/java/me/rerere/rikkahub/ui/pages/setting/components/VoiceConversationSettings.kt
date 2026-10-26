package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import me.rerere.rikkahub.ui.pages.chat.GEMINI_LIVE_LOCATIONS
import me.rerere.rikkahub.ui.pages.chat.GeminiLiveCredentials
import me.rerere.rikkahub.ui.pages.chat.VoiceConversationMode
import me.rerere.rikkahub.ui.pages.chat.parseGeminiLiveServiceAccount
import me.rerere.rikkahub.ui.pages.chat.toGeminiLiveCredentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

@Composable
internal fun VoiceConversationSettings(
    providers: List<ProviderSetting.OpenAI>,
    initialProviderId: Uuid?,
    busy: Boolean,
    error: String?,
    onSaveGpt: (providerId: Uuid, apiKey: String) -> Unit,
    onSaveLegacy: () -> Unit,
    googleProviders: List<ProviderSetting.Google> = emptyList(),
    initialGoogleProviderId: Uuid? = null,
    initialMode: VoiceConversationMode = VoiceConversationMode.GPT,
    onSaveGemini: (GeminiLiveCredentials) -> Unit,
) {
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var selectedGoogleProviderId by remember(initialGoogleProviderId) {
        mutableStateOf(
            initialGoogleProviderId
                ?: googleProviders.firstOrNull { it.toGeminiLiveCredentials().isComplete }?.id
                ?: googleProviders.firstOrNull()?.id
        )
    }
    val googleProvider = googleProviders.firstOrNull { it.id == selectedGoogleProviderId }
    var googleCredentials by remember(selectedGoogleProviderId, googleProvider) {
        mutableStateOf(googleProvider?.toGeminiLiveCredentials() ?: GeminiLiveCredentials())
    }
    var selectedProviderId by remember(initialProviderId) {
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("voice_conversation_settings")
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("对话模式", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                "保存后，回到聊天页点击“语音”开始对话。GPT Live 和 Gemini Live 支持实时听说，无需另配语音识别或朗读。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceConversationMode.entries.forEach { option ->
                OutlinedButton(
                    onClick = { mode = option },
                    enabled = !busy,
                    modifier = Modifier.testTag("voice_mode_${option.preferenceValue}"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (mode == option) "✓ ${option.label}" else option.label)
                }
            }
        }
        if (mode == VoiceConversationMode.LEGACY) {
            CardGroup {
                item(
                    headlineContent = { Text("使用当前聊天模型") },
                    supportingContent = {
                        Text("语音识别转为文字后交给当前聊天模型，回复由朗读服务播报。请在语音识别 Tab 选择支持自动断句的服务，在朗读 Tab 选择播报服务。")
                    },
                )
            }
        } else if (mode == VoiceConversationMode.GEMINI) {
            GeminiLiveSetupFields(
                providers = googleProviders,
                selectedProviderId = selectedGoogleProviderId,
                credentials = googleCredentials,
                busy = busy,
                onSelectProvider = { selectedGoogleProviderId = it },
                onCredentialsChange = { googleCredentials = it },
            )
        } else {
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
                item(
                    headlineContent = { Text("任务助手") },
                    supportingContent = {
                        Text(
                            "使用所在聊天的文本模型回答问题和执行工具，结果由 GPT Live 播报。在聊天中选择任务模型；未选择时使用纯语音模式。"
                        )
                    },
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
                                    "填写 API Key 后即可保存。"
                                } else {
                                    "保存到所选提供商，与文字聊天共用此 Key。"
                                }
                            )
                        },
                    )
                }
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
            onClick = {
                when (mode) {
                    VoiceConversationMode.GEMINI -> onSaveGemini(googleCredentials)
                    VoiceConversationMode.GPT -> provider?.let { onSaveGpt(it.id, apiKey.trim()) }
                    VoiceConversationMode.LEGACY -> onSaveLegacy()
                }
            },
            enabled = !busy && when (mode) {
                VoiceConversationMode.GEMINI -> googleCredentials.isComplete
                VoiceConversationMode.GPT -> provider != null && apiKey.isNotBlank()
                VoiceConversationMode.LEGACY -> true
            },
            modifier = Modifier.fillMaxWidth().testTag("voice_save_configuration"),
            shapes = ButtonDefaults.shapes(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).testTag("voice_busy"),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (busy) "正在保存…" else "保存语音配置")
        }
    }
}

@Composable
private fun GeminiLiveSetupFields(
    providers: List<ProviderSetting.Google>,
    selectedProviderId: Uuid?,
    credentials: GeminiLiveCredentials,
    busy: Boolean,
    onSelectProvider: (Uuid?) -> Unit,
    onCredentialsChange: (GeminiLiveCredentials) -> Unit,
) {
    var showProviderMenu by remember { mutableStateOf(false) }
    var showLocationMenu by remember { mutableStateOf(false) }
    var serviceAccountJson by remember(selectedProviderId) { mutableStateOf("") }
    var importError by remember(selectedProviderId) { mutableStateOf<String?>(null) }
    CardGroup {
        item(
            headlineContent = { Text("Gemini 3.8 Live") },
            supportingContent = {
                Text("使用 Google Cloud 服务账号；项目需启用 Vertex AI API 和结算，并授予服务账号 Vertex AI User 权限。此模型不支持 Express API Key。")
            },
        )
        formItem(headlineContent = { Text("Google Cloud 配置") }) {
            Box {
                OutlinedButton(
                    onClick = { showProviderMenu = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_google_provider_selector"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        providers.firstOrNull { it.id == selectedProviderId }?.name ?: "新建 Google Cloud 配置",
                        modifier = Modifier.weight(1f),
                    )
                    Text("切换", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = showProviderMenu && !busy, onDismissRequest = { showProviderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("新建 Google Cloud 配置") },
                        onClick = {
                            onSelectProvider(null)
                            showProviderMenu = false
                        },
                        modifier = Modifier.testTag("voice_google_provider_new"),
                    )
                    providers.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = {
                                onSelectProvider(candidate.id)
                                showProviderMenu = false
                            },
                            modifier = Modifier.testTag("voice_google_provider_${candidate.id}"),
                        )
                    }
                }
            }
        }
        formItem(headlineContent = { Text("服务账号 JSON") }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = serviceAccountJson,
                    onValueChange = {
                        serviceAccountJson = it
                        importError = null
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_google_service_account_json"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    maxLines = 3,
                    placeholder = { Text("粘贴 Google Cloud 下载的服务账号 JSON") },
                    supportingText = {
                        Text(
                            if (credentials.privateKey.isBlank()) "内容已遮蔽，读取后自动填写项目和账号。" else "已配置服务账号密钥，可导入新的 JSON 替换。"
                        )
                    },
                    isError = importError != null,
                )
                OutlinedButton(
                    onClick = {
                        try {
                            onCredentialsChange(parseGeminiLiveServiceAccount(serviceAccountJson, credentials))
                            serviceAccountJson = ""
                            importError = null
                        } catch (e: IllegalArgumentException) {
                            importError = e.message
                        } catch (e: IllegalStateException) {
                            importError = e.message
                        }
                    },
                    enabled = !busy && serviceAccountJson.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("voice_google_import_json"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("读取服务账号")
                }
                importError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("voice_google_import_error")
                    )
                }
            }
        }
        formItem(headlineContent = { Text("项目 ID") }) {
            OutlinedTextField(
                value = credentials.projectId,
                onValueChange = { onCredentialsChange(credentials.copy(projectId = it)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("voice_google_project_id"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                supportingText = { Text("可填写已授权服务账号使用的其他 Google Cloud 项目。") },
            )
        }
        item(
            modifier = Modifier.testTag("voice_google_service_account_email"),
            headlineContent = { Text("服务账号") },
            supportingContent = {
                Text(credentials.serviceAccountEmail.ifBlank { "尚未导入服务账号 JSON" })
            },
        )
        formItem(headlineContent = { Text("区域") }) {
            Box {
                OutlinedButton(
                    onClick = { showLocationMenu = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_google_location_selector"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(credentials.location, modifier = Modifier.weight(1f))
                    Text("切换", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = showLocationMenu && !busy, onDismissRequest = { showLocationMenu = false }) {
                    GEMINI_LIVE_LOCATIONS.forEach { location ->
                        DropdownMenuItem(
                            text = { Text(location) },
                            onClick = {
                                onCredentialsChange(credentials.copy(location = location))
                                showLocationMenu = false
                            },
                            modifier = Modifier.testTag("voice_google_location_$location"),
                        )
                    }
                }
            }
        }
    }
}
