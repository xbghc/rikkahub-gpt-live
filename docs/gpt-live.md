# GPT Live

本仓库只维护一个功能分支：`codex/vercel-gpt-live-release`。`master` 保留原 RikkaHub 基线，发布版本以 tag 标记。原 `feat/gpt-live` 的 backend 委派已整合到现有语音流程，不再维护另一套 GPT Live 客户端、设置页或发布分支。

## 配置

1. 在聊天中选择任务助手使用的文本模型，例如 GPT、Claude 或其他已配置模型。该模型继续使用原来的供应商凭据、系统提示、工具、MCP 与审批设置。
2. 打开“设置 → 语音服务 → 语音对话”，选择 GPT Live 提供商、填写 API Key 并保存。Vercel AI Gateway 使用 `openai/gpt-live-1` 和原生 `/v1/live/sessions` WebSocket 协议；OpenAI 使用 `gpt-live-1`。
3. 回到聊天页，选择用于处理问题和工具任务的文本模型。开始语音不会替换文字聊天模型；Live 与任务助手分别使用各自的凭据。
4. 点击“语音”，允许麦克风权限后开始对话。未选择文本模型时仍可使用纯语音模式，任务委派会明确提示未配置 backend。

“语音对话”是原有语音服务页的第三个 Tab。GPT Live、Gemini Live（Google Cloud 服务账号）和“语音识别与朗读”在这里选择并保存；原有朗读和语音识别 Tab 继续管理各自的服务。保存只更新配置，不申请麦克风权限，也不发起实时连接。

聊天页的“语音”会直接启动已保存的模式，旁边的设置按钮跳到“语音对话”Tab。尚未配置、提供商被禁用或模型不可用时也会跳到此处，不再另弹配置窗口。原来保存的 GPT Live 提供商及 Key 可以继续使用，Gemini Live 也支持保存后直接启动。连接 Gemini Live 期间显示“连接中”，可用“结束”取消认证和连接。

现有 Gemini Live 和普通 ASR + 聊天 + TTS 入口继续保留；本次 backend 委派接入 GPT Live。

## 对话与任务

- GPT Live 负责实时听说和字幕。当前助手的系统提示及最近聊天文本会传给语音会话。
- 收到 `session.delegation.created` 后，从连续字幕构造请求，交给当前聊天的 `ChatService` 队列；字幕本身不会逐条触发模型调用。
- 同一段输入的重复委派共享助手任务和结果。字幕可能迟到，客户端会短暂等待；无法识别请求时要求澄清。
- 任务使用当前聊天模型、工具和审批流程。等待审批时语音连接继续，可在聊天里批准或拒绝。助手最终可见文字按 UTF-8 字节分片回传给 Live，由 Live 播报；过长的结果会提示查看聊天。
- 语音与助手生成分别持有任务，避免语音连接阻塞后台模型队列。普通 TTS 不重复朗读委派结果。
- “结束”、离开页面或应用进入后台会关闭语音，并撤销本次语音尚未完成的助手任务。迟到结果不会发送给下一次语音会话。取消无法撤回已经执行的工具操作。
- 语音打断不会自动取消正在执行的后台任务。需要停止时使用结束按钮，或聊天中的停止操作。
- 删除、恢复或移动聊天到另一个助手时会关闭原语音连接，避免保存到过期会话。
- 委派请求和回复按普通聊天保存；结束时另外保存语音字幕。原始音频不保存。

此实现保留发布版的页面内语音生命周期，不使用旧功能分支的后台麦克风前台服务。

## 构建与发布

唯一功能分支的 push 和以该分支为目标的 PR 运行 `Android Debug Build`。CI 检查格式、运行 `speech`/`app` 单元测试、构建 APK，并执行语音入口 UI 测试。

功能分支的 push 或手动运行通过全部检查后，独立发布任务会自动创建 GitHub 预发布，附上同一次构建的 arm64 APK 和 `SHA256SUMS`。PR 仅运行验证，不发布。标签为 `gpt-live-debug-<run_id>`，每次构建保留独立版本，不覆盖已有发布标签；重新运行已发布的构建会保留原附件。附件上传完成后才公开预发布，上传失败可重跑发布任务以完成草稿。

在仓库 Releases 页面直接下载 APK，无需解压 Actions Artifact。Actions 仍保留 APK 和测试报告 7 天；Release 附件不受这 7 天保留期影响。发布任务需要仓库的 `GITHUB_TOKEN` 具有 `contents: write`，构建和 PR 任务保持只读。

CI 沿用已发布 APK 的 Debug 签名证书，并在构建前后校验。缓存缺失或证书不符时必须停止，不能生成替代密钥继续发布。保留现有发布分支也保留其签名缓存作用域。Debug 包名为 `me.rerere.rikkahub.debug`。

本地准备 JDK 21、Android SDK、Node 24、pnpm 11，并初始化 submodule：

```sh
git submodule update --init --recursive
cd web-ui
pnpm install --frozen-lockfile
cd ..
./gradlew spotlessCheck -Prikkahub.firebase.enabled=false --no-configuration-cache
./gradlew :speech:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubDebug -Prikkahub.firebase.enabled=false
```

核心位置：

- `speech/.../live/GptLiveConversation.kt`：唯一 GPT Live WebSocket 与音频实现、委派事件和回传。
- `app/.../service/LiveAssistantConversation.kt`：串行协调字幕与委派，管理连接结束时的任务取消。
- `LiveDelegationCoordinator.kt`：字幕上下文、重复请求去重和结果隔离。
- `ChatService.kt`、`LiveChatTask.kt`、`MessageQueue.kt`：现有助手生成、审批和任务归属。
- `app/.../ui/pages/chat/VoiceMode.kt`、`app/.../ui/pages/setting/SettingSpeechPage.kt`、`components/VoiceConversationSettings.kt`：统一语音入口及语音服务配置。

单元测试覆盖模拟 WebSocket 委派回传、无后台的语音兼容、重复字幕/委派、审批等待期间继续听说、取消后的迟到结果、Unicode 分片、队列和工具归属。真实 API、麦克风回声、蓝牙设备和真机升级仍需要设备验证。

## 上游同步

从 2026-10-21 起，通过已配置的每日自动任务检查 `rikkahub/rikkahub:master`。有新增提交时，在唯一功能分支上 rebase，完成冲突处理、测试与构建后使用带精确旧 SHA 的 `--force-with-lease` 推送；`master` 仅快进到上游。没有更新时不改动、不重复通知。发布标签和原有签名继续保留。

每次 rebase 后继续完成验证和发版，无需重复确认；发布后核对附件并提供下载链接，遇到阻塞时说明原因。

当前基线为上游 `ad2c4430`（3.1.1）。App 构建和测试使用 `github` 渠道，APK 路径为 `app/build/outputs/apk/github/debug/`。版本为 `3.1.1-gpt-live`，versionCode 为 199，高于先前的 GPT Live APK，支持同签名覆盖升级。
