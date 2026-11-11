package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

private const val REPOSITORY = "xbghc/rikkahub-gpt-live"
private const val RELEASES_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
private const val SIGNING_CERTIFICATE = "6c2d6dfdac13e1901cbec04feba5f10271747230ce21010f5d4e4608658b9c07"
private val RELEASE_TAG = Regex("gpt-live-debug-[0-9]+")
private val SHA256 = Regex("[0-9a-f]{64}")
private val COMMIT = Regex("[0-9a-f]{40}")
private val ASSET_NAME = Regex("[A-Za-z0-9._+-]+\\.apk")
private val updateJson = Json { ignoreUnknownKeys = true }

internal class GptLiveReleaseSource(private val client: OkHttpClient) {
    suspend fun latest(supportedAbis: List<String>, applicationId: String): UpdateInfo? {
        // /releases/latest excludes prereleases, which are the GPT Live distribution channel.
        val releases = updateJson.decodeFromString<List<GptLiveRelease>>(get(RELEASES_URL).decodeToString())
        for (release in releases.filter {
            !it.draft && it.publishedAt != null && RELEASE_TAG.matches(
                it.tag
            )
        }.sortedByDescending { it.publishedAt }) {
            val manifestAsset = release.assets.singleOrNull { it.name == "update.json" && it.state == "uploaded" }
                ?: continue
            if (!isReleaseAssetUrl(manifestAsset.url, release.tag, manifestAsset.name)) continue
            val manifest = decodeGptLiveUpdateManifest(get(manifestAsset.url), manifestAsset.digest) ?: continue
            val update = manifest.toUpdateInfo(release, supportedAbis, applicationId)
            if (update != null) return update
        }
        return null
    }

    private suspend fun get(url: String): ByteArray = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RikkaHub-GPT-Live")
            .build()
    ).await().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to check GPT Live updates (HTTP ${response.code})")
        response.body.bytes()
    }
}

internal fun decodeGptLiveUpdateManifest(bytes: ByteArray, digest: String?): GptLiveUpdateManifest? {
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (digest != "sha256:$sha256") return null
    return try {
        updateJson.decodeFromString<GptLiveUpdateManifest>(bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    }
}

@Serializable
internal data class GptLiveRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("target_commitish") val commit: String,
    val draft: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GptLiveReleaseAsset>,
)

@Serializable
internal data class GptLiveReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val state: String = "uploaded",
    val digest: String? = null,
)

@Serializable
internal data class GptLiveUpdateManifest(
    val schemaVersion: Int,
    val branch: String,
    val releaseTag: String,
    val sourceCommit: String,
    val version: String,
    val versionCode: Long,
    val applicationId: String,
    val signingCertificateSha256: String,
    val changelog: String,
    val downloads: List<GptLiveManifestDownload>,
) {
    fun toUpdateInfo(
        release: GptLiveRelease,
        supportedAbis: List<String>,
        installedApplicationId: String
    ): UpdateInfo? {
        if (schemaVersion != 1 || branch != "gpt-live" || release.draft ||
            !RELEASE_TAG.matches(release.tag)) {
            return null
        }
        val publishedAt = release.publishedAt ?: return null
        if (releaseTag != release.tag || sourceCommit != release.commit || !COMMIT.matches(sourceCommit)) return null
        if (version.isBlank() || versionCode <= 0 || applicationId != installedApplicationId) return null
        if (applicationId != "me.rerere.rikkahub.debug" || signingCertificateSha256 != SIGNING_CERTIFICATE) return null
        val download = (supportedAbis + "universal").firstNotNullOfOrNull { abi ->
            downloads.singleOrNull { it.abi == abi }?.takeIf { item ->
                val asset = release.assets.singleOrNull { it.name == item.name }
                ASSET_NAME.matches(item.name) && SHA256.matches(item.sha256) && item.size > 0 &&
                    isReleaseAssetUrl(item.url, release.tag, item.name) &&
                    asset != null && asset.state == "uploaded" && asset.url == item.url && asset.size == item.size &&
                    asset.digest == "sha256:${item.sha256}"
            }
        } ?: return null
        return UpdateInfo(
            version = version,
            versionCode = versionCode,
            publishedAt = publishedAt,
            changelog = changelog,
            downloads = listOf(
                UpdateDownload(
                    name = download.name,
                    url = download.url,
                    size = String.format(Locale.ROOT, "%.1f MB", download.size / 1_000_000.0),
                    sha256 = download.sha256,
                    versionCode = versionCode,
                    applicationId = applicationId,
                    bytes = download.size,
                )
            ),
        )
    }
}

@Serializable
internal data class GptLiveManifestDownload(
    val abi: String,
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

private fun isReleaseAssetUrl(value: String, tag: String, name: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.host == "github.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.pathSegments == listOf("xbghc", "rikkahub-gpt-live", "releases", "download", tag, name)
}
