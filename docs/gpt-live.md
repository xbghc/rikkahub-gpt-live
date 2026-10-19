# GPT Live 语音对话

此分支为 RikkaHub 增加 GPT Live 双向语音会话。GPT Live 负责听说和自然的语音交互；需要回答问题、执行工具或处理任务时，通过 Client Delegation 交给当前聊天的 RikkaHub 助手，再把助手结果送回语音会话。

## 配置和启动

1. 在 RikkaHub 中配置当前助手的聊天模型。可继续使用已有的 OpenAI、Claude 或其他受支持的供应商，以及原来的工具设置。
2. 打开 **设置 → 语音 → GPT Live**，启用 GPT Live，填写有权使用所选 Live 模型的 API Key。
3. 默认提供商为 OpenAI：模型 `gpt-live-1`，声音 `marin`，WebSocket 地址 `wss://api.openai.com/v1/live/sessions`。也可以在连接设置中选择 **Vercel AI Gateway**，自动填入 `wss://ai-gateway.vercel.sh/v1/live/sessions` 和模型 `openai/gpt-live-1`，并填写 Vercel AI Gateway API Key。切换提供商会清空先前的密钥，避免误发凭据。
   - Vercel 必须使用 **native Live** 的 `/v1/live/sessions`，不是普通 HTTPS Base URL，也不是 `/v4/ai/live-model` 的标准化事件协议。
   - 仍可手动修改 WebSocket 地址和模型以使用其他支持原生 GPT Live 协议的服务。
4. 可以修改声音和语音指令。默认指令要求自然使用用户的语言、简洁说话，并将实质问题和任务委派给当前助手。
5. 返回聊天，点击输入区的 **GPT Live** 入口。首次使用授予麦克风权限，然后点击开始。Android 13 及以上还会请求通知权限，用于展示会话控制。

Live API Key 与当前助手的模型凭据分别配置。使用 Claude 处理委派任务时，仍需为 GPT Live 配置语音会话的凭据。启动时会检查助手模型是否已选择。

设置项会随 RikkaHub 设置持久化；关闭功能不会自动清空配置，可在设置中单独清空 Key。遵循应用现有的设置备份行为，导出或分享配置时应留意其中的凭据。

## 会话中的操作

| 操作或状态 | 行为 |
| --- | --- |
| 静音 | 停止并释放本地麦克风录音器，确认服务器静音状态；语音会话保持连接 |
| 取消静音 | 先确认服务器恢复输入，再重新打开本地麦克风 |
| 结束 | 停止本地音频，撤销本次语音会话尚未完成的委派，请求服务器关闭会话 |
| 取消任务 | 取消本次语音会话的待处理委派，继续保持听说连接 |
| 查看字幕 | 分别查看用户和 GPT Live 的连续字幕；字幕可能延迟、重叠或尚未完整 |
| 等待工具审批 | 语音连接继续，提示到聊天中审批；批准或拒绝后沿用现有工具流程 |
| 切换聊天 | 原会话继续运行，并显示返回原聊天的入口；同一进程同时只有一个 Live 会话 |
| 切到后台或锁屏 | 由麦克风/媒体播放前台服务维持音频，通知提供静音、结束和返回聊天操作 |

开启 GPT Live 时，旧版语音输入入口和自动 TTS 会让出音频。启动前如有旧语音录入、消息编辑、待恢复的消息队列或待审批工具，请先完成对应操作。

服务器自然语音打断与取消助手任务是两个独立操作。若希望撤销已经委派的工作，请使用 **取消任务**。取消无法撤回工具已经执行的外部操作。

## 对话、上下文和任务

- 会话启动时发送当前聊天最近的用户和助手文本作为上下文，有数量和长度上限。
- GPT Live 的 `delegation.created` 事件不包含完整任务文本。客户端从最近收到的连续字幕构造请求，并短暂等待迟到字幕。
- 同一段输入的重复委派会共享已有助手结果，避免重复调用工具。出现新输入后，新任务进入当前聊天的消息队列。
- 委派使用当前助手的模型、系统提示、工具和审批机制。后台返回给 Live 的内容仅取助手最终可见文本。
- 委派请求和回复按普通聊天消息保存。连续语音字幕保留在当前进程的会话状态中，新建语音会话会替换它们；本功能不保存原始音频文件。
- 取消任务后会丢弃其迟到结果。结束旧会话、开始新会话后，旧 WebSocket、通知动作和任务回调不能控制新会话。
- 删除、恢复聊天或切换该聊天所属助手会关闭当前语音会话，避免把结果写入过期上下文。

## 连接和设备行为

Live 输入输出均采用 24 kHz、单声道、PCM16 little-endian 音频。客户端持续发送有节奏的输入帧；静音时发送静音帧保持服务器时间轴推进。

连接仅在收到 `session.started` 后开始上传音频。关闭时请求 `session.close` 并等待真实的 `session.closed`；没有收到确认时，界面明确区分未确认的关闭与服务器确认的时长。会话时长是累计值，不会把各次 usage 快照相加。

音频输入、网络发送和播放缓冲都有上限。连接中断、输入/播放无法继续、音频焦点丢失或 Android 中止录音时，会释放资源并显示错误。重新开始会创建全新会话；不会自动重放历史音频或未完成工具。

前台服务需要从可见界面的用户操作启动。进程被系统终止后不会自动重开麦克风。后台和锁屏行为还受设备系统的电池管理及音频设备实现影响。

## 开发和验证

### GitHub Actions 自动构建与发布

[Android Debug Build](https://github.com/xbghc/rikkahub-gpt-live/actions/workflows/android-build.yml) 会在推送分支和创建或更新 PR 时运行 Kotlin 格式检查、`app` 与 `speech` 的 JVM 单元测试，并构建 Debug APK。同一分支的较旧构建会被新提交取消，避免重复占用构建资源。

本仓库 `feat/gpt-live` 分支由 push 或手动触发的构建成功后，会调用 **Publish GPT Live APKs**，复用该次构建的三个 APK，创建独立预发布版本 `gpt-live-debug-<构建 run ID>`。PR 仅运行构建检查，不发布。

发布完成后，在 [Releases](https://github.com/xbghc/rikkahub-gpt-live/releases) 打开对应预发布版本，直接下载原始 `.apk` 文件。页面同时提供 `SHA256SUMS` 校验文件：

| 发布文件 | 适用设备或用途 |
| --- | --- |
| `app-arm64-v8a-debug.apk` | 大多数 Android 手机 |
| `app-x86_64-debug.apk` | x86_64 模拟器及设备 |
| `app-universal-debug.apk` | 同时包含上面两种架构的通用包 |
| `SHA256SUMS` | 三个 APK 的 SHA-256 校验值 |

构建运行的 **Artifacts** 保留 7 天，仅作为备用下载；下载后解压 ZIP 取得 APK。`rikkahub-unit-test-reports` 包含单元测试结果和 HTML 报告，测试失败时也会尝试上传。

发布工作流自身修改触发运行时，会补发布 `feat/gpt-live` 分支最新一次成功的构建。也可通过 **Publish GPT Live APKs** 的 `workflow_dispatch`，填写 `build-run-id` 指定一次成功的构建进行补发。构建与发布工作流的 **Run workflow** 手动按钮，均需相应工作流文件进入仓库默认分支后才会显示。

#### Release 发布权限

构建和下载 Artifacts 使用内置 `GITHUB_TOKEN`。发布步骤优先使用仓库 Secret `RELEASE_TOKEN`，未配置时使用内置令牌。同次构建完成后的自动发布已使用内置令牌验证通过，无需额外 Secret。

补发历史构建时，目标提交中工作流文件的变动可能触发 GitHub 的额外权限检查，返回 `403 Resource not accessible by integration` 或 404。若遇到该情况，可配置具有 Workflows 写权限的发布令牌；内置 `GITHUB_TOKEN` 无法获得该权限：

1. 创建只授权 `xbghc/rikkahub-gpt-live` 的 fine-grained personal access token，设置 **Contents: Read and write**、**Workflows: Read and write**。
2. 在仓库 **Settings → Secrets and variables → Actions** 中添加 Secret，名称为 `RELEASE_TOKEN`，值为该令牌。
3. 重跑失败的发布任务，即可复用已有 APK；后续成功构建会自动调用发布流程。

令牌仅传递给发布步骤，不会写入 APK 或构建日志。权限要求见 [GitHub Create a release 文档](https://docs.github.com/en/rest/releases/releases#create-a-release)。

自动构建无需配置仓库 Secrets、正式签名或 `google-services.json`。工作流使用 `-Prikkahub.firebase.enabled=false`，关闭 Firebase 初始化、统计和崩溃上报；GPT Live 与助手模型的 API Key 在安装后填写。

Debug 版本的包名是 `me.rerere.rikkahub.debug`，可与正式版共存。工作流缓存开发签名，尽量支持连续构建间覆盖安装；缓存清理、分支隔离或与本地构建使用不同签名时，Android 可能要求先卸载旧 Debug 版，此时请先备份应用数据。上游的 `Daily Build` 仍是单独的正式签名 Release 流程，依赖它原有的 Secrets。

### 本地构建

主要代码位置：

| 位置 | 职责 |
| --- | --- |
| `speech/src/main/java/me/rerere/live/` | Live 协议、连接状态、确认匹配、发送边界与凭据脱敏 |
| `app/.../service/GptLiveSessionManager.kt` | 应用级会话、生命周期、字幕及委派协调 |
| `app/.../service/GptLiveAudio.kt` | 录音、播放、静音、音频焦点与设备释放 |
| `app/.../service/GptLiveForegroundService.kt` | 前台服务、通知及会话 token 隔离 |
| `app/.../service/LiveDelegationCoordinator.kt` | 连续字幕上下文、重复请求复用和迟到结果隔离 |
| `app/.../service/ChatService.kt` | 当前助手任务队列、审批续接及语音任务归属 |
| `app/.../ui/pages/chat/GptLiveConversationPanel.kt` | 聊天入口、状态、字幕和会话控制 |
| `app/.../ui/pages/setting/components/GptLiveSettingsForm.kt` | GPT Live 设置 |

准备 JDK 21、Node.js 24、pnpm 11 和 Android SDK。自动构建所用的 SDK、NDK 与 CMake 版本见 [工作流配置](../.github/workflows/android-build.yml)。本地 SDK 路径放入不入库的 `local.properties`，或使用 `ANDROID_HOME`。

```sh
git submodule update --init --recursive
cd web-ui
pnpm install --frozen-lockfile
cd ..
./gradlew :speech:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug -Prikkahub.firebase.enabled=false
./gradlew spotlessCheck -Prikkahub.firebase.enabled=false
```

Firebase 默认仍启用。需要使用自己的 Firebase 项目时，在 `app/` 放置匹配构建包名的 `google-services.json`，并省略 `-Prikkahub.firebase.enabled=false`；Debug 配置应包含 `me.rerere.rikkahub.debug`。该文件不入库。若在同一工作目录切换 Firebase 构建开关，先执行 `./gradlew :app:clean -Prikkahub.firebase.enabled=false`，清理上一种配置生成的资源。

新增 JVM 用例覆盖协议字段、启动及静音确认、关闭超时、重复/未知事件、缓冲压力、Unicode 文本分片、凭据脱敏、字幕延迟、任务取消、审批等待、队列归属、PCM 缓冲和设置兼容性。真实 API 对话、麦克风回声、蓝牙/有线设备、锁屏及系统杀进程仍需用实际账号和设备验收。

建议真机验收路径：启动并连续交谈 → 委派当前助手处理问题 → 工具待审批时继续说话 → 批准后听取结果 → 静音/取消静音 → 取消排队任务 → 切换聊天并从通知返回 → 锁屏 → 结束 → 立即重连。另检查无权限、错误 Key、断网、撤销录音权限和删除聊天时的退出提示。

## 协议依据

- [OpenAI GPT Live 指南](https://developers.openai.com/api/docs/guides/live)
- [OpenAI Live Client Delegation](https://developers.openai.com/api/docs/guides/live-delegation)
- [Vercel AI Gateway GPT-Live native WebSocket](https://vercel.com/docs/ai-gateway/modalities/realtime/gpt-live)
- [Android 前台服务启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)
