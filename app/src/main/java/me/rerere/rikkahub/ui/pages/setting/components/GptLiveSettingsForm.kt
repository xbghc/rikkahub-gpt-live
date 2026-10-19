package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.GptLiveSettings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
fun GptLiveSettingsForm(
    settings: GptLiveSettings,
    onUpdateSettings: ((GptLiveSettings) -> GptLiveSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var keyVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardGroup {
                switchItem(
                    checked = settings.enabled,
                    onCheckedChange = { value -> onUpdateSettings { it.copy(enabled = value) } },
                    headlineContent = { Text(stringResource(R.string.gpt_live_enable)) },
                    supportingContent = { Text(stringResource(R.string.gpt_live_description)) },
                )
            }
        }
        item {
            CardGroup(title = { Text(stringResource(R.string.gpt_live_connection)) }) {
                formItem {
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { value -> onUpdateSettings { it.copy(apiKey = value) } },
                        label = { Text(stringResource(R.string.gpt_live_api_key)) },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { keyVisible = !keyVisible }) {
                                Text(stringResource(if (keyVisible) R.string.gpt_live_hide_key else R.string.gpt_live_show_key))
                            }
                        },
                        isError = settings.enabled && settings.apiKey.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                formItem {
                    OutlinedTextField(
                        value = settings.endpoint,
                        onValueChange = { value -> onUpdateSettings { it.copy(endpoint = value) } },
                        label = { Text(stringResource(R.string.gpt_live_endpoint)) },
                        supportingText = { Text(stringResource(R.string.gpt_live_endpoint_description)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = settings.enabled && !settings.endpoint.trim().startsWith("wss://"),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                formItem {
                    OutlinedTextField(
                        value = settings.model,
                        onValueChange = { value -> onUpdateSettings { it.copy(model = value) } },
                        label = { Text(stringResource(R.string.gpt_live_model)) },
                        isError = settings.enabled && settings.model.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
        item {
            CardGroup(title = { Text(stringResource(R.string.gpt_live_speaking)) }) {
                formItem {
                    OutlinedTextField(
                        value = settings.voice,
                        onValueChange = { value -> onUpdateSettings { it.copy(voice = value) } },
                        label = { Text(stringResource(R.string.gpt_live_voice)) },
                        isError = settings.enabled && settings.voice.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                formItem {
                    OutlinedTextField(
                        value = settings.instructions,
                        onValueChange = { value -> onUpdateSettings { it.copy(instructions = value) } },
                        label = { Text(stringResource(R.string.gpt_live_instructions)) },
                        supportingText = { Text(stringResource(R.string.gpt_live_instructions_description)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.gpt_live_background_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
