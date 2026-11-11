package me.rerere.rikkahub.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resumeWithException

sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateInstallState
    data object Verifying : UpdateInstallState
    data class Ready(val download: UpdateDownload, val file: File) : UpdateInstallState
    data class Error(val download: UpdateDownload, val message: String) : UpdateInstallState
}

/** Keeps a download alive when its sheet is closed, without exporting the APK to shared storage. */
class InAppUpdater(
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    private val mutableState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state = mutableState.asStateFlow()
    private var downloadJob: Job? = null
    private var generation = 0L

    private var activeCall: Call? = null

    fun download(context: Context, download: UpdateDownload) {
        if (BuildConfig.IS_PLAY_STORE || downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        val downloadGeneration = synchronized(mutableState) {
            generation += 1
            mutableState.value = UpdateInstallState.Downloading(0, download.bytes)
            generation
        }
        downloadJob = appScope.launch(Dispatchers.IO) {
            var temporaryFile: File? = null
            var call: Call? = null
            try {
                currentCoroutineContext().ensureActive()
                require(download.applicationId == appContext.packageName) { "更新包与当前应用不兼容" }
                require(download.bytes in 1..MAX_APK_BYTES) { "更新包大小无效" }
                require(download.sha256.matches(Regex("[0-9a-f]{64}"))) { "更新包校验信息无效" }
                val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val pendingFile = File.createTempFile("update-", ".part", directory)
                temporaryFile = pendingFile
                val downloadCall = client.newCall(Request.Builder().url(download.url).build())
                call = downloadCall
                synchronized(mutableState) {
                    if (generation == downloadGeneration) activeCall = downloadCall else downloadCall.cancel()
                }
                downloadCall.awaitUpdateResponse().use { response ->
                    check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                    val body = response.body
                    val contentLength = body.contentLength()
                    check(contentLength <= 0 || contentLength == download.bytes) { "更新包大小与发布信息不符" }
                    body.byteStream().use { input ->
                        pendingFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            var lastReported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                downloaded += read
                                check(downloaded <= download.bytes) { "更新包大小超出发布信息" }
                                output.write(buffer, 0, read)
                                if (downloaded - lastReported >= 256 * 1024 || downloaded == download.bytes) {
                                    publish(
                                        downloadGeneration,
                                        UpdateInstallState.Downloading(downloaded, download.bytes)
                                    )
                                    lastReported = downloaded
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                publish(downloadGeneration, UpdateInstallState.Verifying)
                verifyFile(appContext, pendingFile, download)
                currentCoroutineContext().ensureActive()
                val verifiedFile = File(directory, "${download.sha256}.apk")
                check(pendingFile.renameTo(verifiedFile)) { "无法保存已验证的更新包" }
                temporaryFile = null
                publish(downloadGeneration, UpdateInstallState.Ready(download, verifiedFile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                publish(downloadGeneration, UpdateInstallState.Error(download, error.message ?: "下载更新失败，请重试"))
            } finally {
                temporaryFile?.delete()
                synchronized(mutableState) {
                    if (activeCall === call) activeCall = null
                }
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        val cancelledCall = synchronized(mutableState) {
            generation += 1
            mutableState.value = UpdateInstallState.Idle
            activeCall.also { activeCall = null }
        }
        cancelledCall?.cancel()
    }

    private fun publish(downloadGeneration: Long, value: UpdateInstallState) {
        synchronized(mutableState) {
            if (generation == downloadGeneration) mutableState.value = value
        }
    }

    /** False with Ready state means that the user must first grant install permission in Settings. */
    fun install(context: Context): Boolean {
        if (BuildConfig.IS_PLAY_STORE) return false
        val ready = state.value as? UpdateInstallState.Ready ?: return false
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) return false
            check(ready.file.isFile) { "更新包已被清理，请重新下载" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("GPT Live update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (error: Exception) {
            mutableState.value = UpdateInstallState.Error(ready.download, error.message ?: "无法打开系统安装界面")
            false
        }
    }

    private fun verifyFile(context: Context, file: File, download: UpdateDownload) {
        require(file.length() == download.bytes) { "更新包下载不完整，请重试" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().toHexString() == download.sha256) { "更新包校验失败，请重新下载" }
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("无法读取更新包")

        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, flags)
        validateUpdateApkIdentity(download, archive.updateIdentity(), installed.updateIdentity())
    }

    private fun PackageInfo.updateIdentity(): UpdateApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            this.signatures
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
        return UpdateApkIdentity(
            packageName = packageName,
            versionCode = code,
            signerDigests = signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString()
            }.toSet()
        )
    }

    private companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}

private suspend fun Call.awaitUpdateResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

internal fun validateUpdateApkIdentity(
    download: UpdateDownload,
    archive: UpdateApkIdentity,
    installed: UpdateApkIdentity,
) {
    require(archive.packageName == installed.packageName && archive.packageName == download.applicationId) {
        "更新包与当前应用不兼容"
    }
    require(archive.versionCode == download.versionCode && archive.versionCode > installed.versionCode) {
        "更新包版本无效或低于已安装版本"
    }
    require(archive.signerDigests.isNotEmpty() && archive.signerDigests == installed.signerDigests) {
        "更新包签名与当前应用不一致，无法覆盖安装"
    }
}
