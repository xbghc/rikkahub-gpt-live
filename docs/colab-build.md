# Colab 构建与发布

`gpt-live` 的可信 push 和手动构建默认使用普通 Colab API（非 Colab Enterprise），消费登录账号的 Colab 权益。默认申请 TPU v5e 标准实例，实际使用其 CPU 编译 Android，不运行模型或模拟器。机器是否可分配由 Colab 决定。

## 发布路径

1. Actions 在固定的源码 SHA 上启动 Colab 编译任务。远端只从公开仓库拉取这一 SHA，使用临时测试签名，运行 Spotless、`speech`/`app` JVM 单测，编译 arm64、x86_64 和 instrumentation APK。
2. 控制器通过后台任务 ID、状态文件和结果轮询等待完成；连接中断不会重新启动编译。下载产物时核对 SHA-256，保存源码 SHA、版本和测试报告清单，然后释放本次申请的机器。失败、超时和取消同样执行清理，工作流另有 `always()` 清理步骤。
3. 独立 Actions 任务接收同一次运行的不可变 artifact，复核来源 SHA、版本、文件摘要和单测结果；从原有 secret 恢复并核对已发布签名，重新签署 app 和测试 APK。校验签名前后除签名文件之外的 ZIP 内容完全一致，并校验最终包名、ABI 和签名。这里不运行 Gradle。
4. Actions 模拟器直接安装预构建 x86_64 APK 和测试 APK。通过 `adb am instrument` 运行语音入口测试，要求测试集合与源码一致、无失败或跳过，且截图齐全。仅 `adb` 返回 0 不能视为成功。
5. 全部通过后，沿用自动预发布脚本：要求 versionCode 高于上一发布；先上传草稿的 arm64 APK、`SHA256SUMS`、`update.json`，再公开。保留已有发布标签及附件。原应用包名和证书不变，支持应用内升级。

PR 始终在 Actions 编译和测试，使用独立临时密钥，不能使用 Colab/Drive 凭据或原签名。发布密钥只进入签名任务，GitHub 写权限只进入发布任务，均不传入 Colab。

## 凭据和缓存

仓库配置：

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Secret | `COLAB_ADC_JSON` | 已授权 `colaboratory` 与 `drive.file` 的用户 OAuth ADC，仅供可信编译控制器使用 |
| Variable | `COLAB_PROJECT_ID` | 已启用普通 Colab API 的 quota project |
| Variable | `COLAB_CACHE_FOLDER_ID` | 用户授权的私有 Drive 缓存文件夹 |
| Secret | `RIKKAHUB_DEBUG_KEYSTORE_BASE64` | 已有原签名，保持不变 |

控制器把 ADC 留在 Actions，只给 Colab 一个短期、严格限于 `drive.file` 的 access token。Drive 以不可变归档和最后写入的 manifest 保存 Gradle 依赖及构建输出，构建时下载到本地 SSD 并核对摘要。缓存按 Gradle、依赖锁文件及工具链版本匹配，源码变更由 Gradle 输入检查决定重编译；缓存不包含 APK、密钥、OAuth 凭据或单测结果，所有单测每次执行。缓存上传失败可继续使用已验证产物，编译、测试、摘要或签名失败则停止发布。

初始化、Drive 下载和解压、缓存回写、APK 传输、设备测试均应单独统计。已有测速中恢复后的编译与单测约 41 秒，修改代码及版本约 63 秒；不能保证所有增量或完整发布都在一分钟内。

## 执行和排错

日常推送 `gpt-live` 自动运行。在 Actions 手动执行 `Android Debug Build` 时可选择 `backend=actions`，在 Colab 资源或认证不可用时使用原生编译路径。两种路径使用相同签名、设备测试与发布关卡；不会因测试失败而自动换后端发布。

本机已登录后也可运行控制器验证公开的固定 SHA（不签署原证书、不发布）：

```sh
python3 -m venv /tmp/rikkahub-colab-python
/tmp/rikkahub-colab-python/bin/pip install -r tools/colab/requirements.txt
COLAB_PROJECT_ID=<project> COLAB_CACHE_FOLDER_ID=<folder> \
  /tmp/rikkahub-colab-python/bin/python tools/colab/build.py --source "$(git rev-parse HEAD)"
```

工作区 `build/colab-state.json` 记录本次 runtime 名称、阶段和清理状态，不含代理 token。异常中断后用同一状态文件执行 `build.py --cleanup`，只清理本次记录的机器。`colab-api-check --drive` 可复查本机账号、API 和原缓存配置。

控制器与失败场景检查：

```sh
python3 -m unittest discover -s tools/colab -p 'test_*.py' -v
```

官方接口：[Colab 创建运行时](https://developers.google.com/colab/api/reference/rest/v1beta/runtimes/create)、[APK 签名](https://developer.android.com/tools/apksigner)、[直接运行 instrumentation 测试](https://developer.android.com/studio/test/command-line)。
