package me.rerere.rikkahub.ui.components.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.UpdateInstallState
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installer = vm.updateChecker.installer
    val installState by installer.state.collectAsStateWithLifecycle()
    var installPermissionMessage by remember { mutableStateOf<String?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            installPermissionMessage = null
            installer.install(context)
        } else {
            installPermissionMessage = "安装更新需要允许此应用安装未知应用。你可以稍后再次点击安装。"
        }
    }
    state.onError {
        Card {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_card_check_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = it.message ?: stringResource(R.string.update_card_unknown_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    state.onSuccess { info ->
        var showDetail by remember(info.versionCode) { mutableStateOf(false) }
        var dismissed by remember(info.versionCode) { mutableStateOf(false) }
        if (info.isNewerThan(BuildConfig.VERSION_CODE.toLong()) && !dismissed) {
            Card(
                onClick = {
                    showDetail = true
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.update_card_new_version_found, info.version),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            enabled = installState == UpdateInstallState.Idle || installState is UpdateInstallState.Error,
                            onClick = { dismissed = true }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MarkdownBlock(
                        content = info.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 200.dp)
                    )
                    when (val progress = installState) {
                        is UpdateInstallState.Downloading -> Text("正在下载更新，点击查看进度")
                        UpdateInstallState.Verifying -> Text("正在验证更新包")
                        is UpdateInstallState.Ready -> Text("更新已下载，点击安装")
                        is UpdateInstallState.Error -> Text(
                            text = progress.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        UpdateInstallState.Idle -> Unit
                    }
                }
            }
        }
        if (showDetail) {
            ModalBottomSheet(
                onDismissRequest = { showDetail = false },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.version,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = remember(info.publishedAt) {
                            runCatching { Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime() }
                                .getOrDefault(info.publishedAt)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when (val progress = installState) {
                        is UpdateInstallState.Downloading -> {
                            val fraction = if (progress.totalBytes > 0) {
                                progress.downloadedBytes.toFloat() / progress.totalBytes
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("正在下载 ${(fraction * 100).toInt()}%")
                            TextButton(onClick = installer::cancel) { Text("取消下载") }
                        }

                        UpdateInstallState.Verifying -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在验证更新包和签名…")
                            TextButton(onClick = installer::cancel) { Text("取消") }
                        }

                        is UpdateInstallState.Ready -> {
                            Text("更新包已验证，点击后由系统确认安装。")
                            installPermissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (!installer.install(context) && installer.state.value is UpdateInstallState.Ready) {
                                        runCatching {
                                            installPermissionLauncher.launch(
                                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                    data = "package:${context.packageName}".toUri()
                                                }
                                            )
                                        }.onFailure {
                                            installPermissionMessage = "无法打开安装权限设置，请在系统设置中允许此应用安装未知应用。"
                                        }
                                    }
                                }
                            ) { Text("安装更新") }
                        }

                        is UpdateInstallState.Error -> {
                            Text(progress.message, color = MaterialTheme.colorScheme.error)
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { installer.download(context, progress.download) }
                            ) { Text("重新下载") }
                        }

                        UpdateInstallState.Idle -> info.downloads.fastForEach { downloadItem ->
                            CardGroup {
                                item(
                                    onClick = { vm.updateChecker.downloadUpdate(context, downloadItem) },
                                    headlineContent = { Text("下载更新") },
                                    supportingContent = { Text("${downloadItem.name} · ${downloadItem.size}") },
                                    leadingContent = { Icon(HugeIcons.Download01, contentDescription = null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
