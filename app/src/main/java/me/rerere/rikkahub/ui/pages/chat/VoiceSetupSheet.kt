package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    googleProviders: List<ProviderSetting.Google> = emptyList(),
    initialGoogleProviderId: Uuid? = null,
    initialGoogleCloud: Boolean = true,
    onStartGemini: ((GeminiLiveCredentials) -> Unit)? = null,
    onCancelPreparation: (() -> Unit)? = null,
) {
    var useGoogleCloud by remember { mutableStateOf(onStartGemini != null && initialGoogleCloud) }
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
                    "直接与模型实时听说，支持打断，无需另配语音识别或朗读。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onStartGemini != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { useGoogleCloud = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("voice_mode_google"),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(if (useGoogleCloud) "✓ Google Cloud" else "Google Cloud")
                    }
                    OutlinedButton(
                        onClick = { useGoogleCloud = false },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("voice_mode_gpt"),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(if (!useGoogleCloud) "✓ GPT Live" else "GPT Live")
                    }
                }
            }
            if (useGoogleCloud) {
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
                    if (useGoogleCloud) {
                        onStartGemini?.invoke(googleCredentials)
                    } else {
                        provider?.let { onStartLive(it.id, apiKey.trim()) }
                    }
                },
                enabled = !busy && if (useGoogleCloud) {
                    googleCredentials.isComplete
                } else {
                    provider != null && apiKey.isNotBlank()
                },
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
                onClick = { if (busy) onCancelPreparation?.invoke() else onDismiss() },
                enabled = !busy || onCancelPreparation != null,
                modifier = Modifier.fillMaxWidth().testTag("voice_setup_cancel"),
            ) {
                Text("取消")
            }
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
