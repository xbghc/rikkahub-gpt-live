package me.rerere.rikkahub.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class GptLiveReleaseSourceTest {
    private val sha256 = "a".repeat(64)
    private val commit = "b".repeat(40)
    private val packageName = "me.rerere.rikkahub.debug"
    private val name = "rikkahub-3.1.1-gpt-live.1-arm64-v8a-debug.apk"
    private val tag = "gpt-live-debug-1234"
    private val url = "https://github.com/xbghc/rikkahub-gpt-live/releases/download/$tag/$name"
    private val apk = GptLiveManifestDownload("arm64-v8a", name, url, sha256, 21_000_000)
    private val release = GptLiveRelease(
        tag = tag,
        commit = commit,
        draft = false,
        publishedAt = "2026-10-25T12:00:00Z",
        assets = listOf(GptLiveReleaseAsset(name, url, apk.size, digest = "sha256:$sha256")),
    )
    private val manifest = GptLiveUpdateManifest(
        schemaVersion = 1,
        branch = "gpt-live",
        releaseTag = tag,
        sourceCommit = commit,
        version = "3.1.1-gpt-live.1",
        versionCode = 200,
        applicationId = packageName,
        signingCertificateSha256 = "6c2d6dfdac13e1901cbec04feba5f10271747230ce21010f5d4e4608658b9c07",
        changelog = "应用内更新",
        downloads = listOf(apk),
    )

    @Test
    fun `same name releases are compared by Android version code`() {
        val update = manifest.toUpdateInfo(release, listOf("arm64-v8a"), packageName)!!
        assertTrue(update.isNewerThan(199))
        assertFalse(update.isNewerThan(200))
        assertFalse(update.isNewerThan(201))
        assertEquals(200L, update.downloads.single().versionCode)
        assertEquals(sha256, update.downloads.single().sha256)
        assertEquals(apk.size, update.downloads.single().bytes)
    }

    @Test
    fun `upstream nightly and other branch releases are rejected`() {
        assertNull(manifest.copy(branch = "master").toUpdateInfo(release, listOf("arm64-v8a"), packageName))
        assertNull(manifest.toUpdateInfo(release.copy(tag = "nightly"), listOf("arm64-v8a"), packageName))
        assertNull(manifest.toUpdateInfo(release.copy(draft = true), listOf("arm64-v8a"), packageName))
        assertNull(manifest.toUpdateInfo(release.copy(publishedAt = null), listOf("arm64-v8a"), packageName))
        assertNull(
            manifest.copy(releaseTag = "gpt-live-debug-9999").toUpdateInfo(release, listOf("arm64-v8a"), packageName)
        )
        assertNull(manifest.copy(sourceCommit = "c".repeat(40)).toUpdateInfo(release, listOf("arm64-v8a"), packageName))
    }

    @Test
    fun `only the installed package and published signing certificate are allowed`() {
        assertNull(manifest.toUpdateInfo(release, listOf("arm64-v8a"), "me.rerere.rikkahub"))
        assertNull(
            manifest.copy(
                signingCertificateSha256 = "c".repeat(64)
            ).toUpdateInfo(release, listOf("arm64-v8a"), packageName)
        )
        assertNull(manifest.copy(versionCode = 0).toUpdateInfo(release, listOf("arm64-v8a"), packageName))
        assertNull(manifest.copy(schemaVersion = 2).toUpdateInfo(release, listOf("arm64-v8a"), packageName))
    }

    @Test
    fun `APK selection follows device ABI preference and rejects incompatible APKs`() {
        assertNull(manifest.toUpdateInfo(release, listOf("x86_64"), packageName))
        assertNotNull(manifest.toUpdateInfo(release, listOf("arm64-v8a", "armeabi-v7a"), packageName))
        val x86 = apk.copy(
            abi = "x86_64",
            name = "test-x86_64.apk",
            url = "https://github.com/xbghc/rikkahub-gpt-live/releases/download/$tag/test-x86_64.apk",
        )
        val allAbis = manifest.copy(downloads = listOf(apk, x86))
        val allAssets = release.copy(assets =
            release.assets + GptLiveReleaseAsset(x86.name, x86.url, x86.size, digest = "sha256:$sha256"))
        assertEquals(
            x86.name,
            allAbis.toUpdateInfo(allAssets, listOf("x86_64", "arm64-v8a"), packageName)!!.downloads.single().name,
        )
    }

    @Test
    fun `checksum size and release asset URL must all match`() {
        assertNull(withDownload(apk.copy(sha256 = "invalid")))
        assertNull(withDownload(apk.copy(sha256 = "c".repeat(64))))
        assertNull(withDownload(apk.copy(size = apk.size + 1)))
        assertNull(withDownload(apk.copy(url = url.replace("https:", "http:"))))
        assertNull(withDownload(apk.copy(url = url.replace("github.com", "github.com.evil.test"))))
        assertNull(withDownload(apk.copy(url = url.replace("xbghc", "rikkahub"))))
        assertNull(withDownload(apk.copy(url = "$url?download=1")))
        assertNull(manifest.toUpdateInfo(release.copy(assets = emptyList()), listOf("arm64-v8a"), packageName))
        assertNull(
            manifest.toUpdateInfo(
                release.copy(assets = release.assets.map { it.copy(state = "new") }),
                listOf("arm64-v8a"),
                packageName
            )
        )
        assertNull(
            manifest.toUpdateInfo(
                release.copy(assets = release.assets.map { it.copy(digest = null) }),
                listOf("arm64-v8a"),
                packageName
            )
        )
    }

    @Test
    fun `JSON parser ignores GitHub extra fields and nullable draft publication dates`() {
        val json = Json { ignoreUnknownKeys = true }
        val releases = json.decodeFromString<List<GptLiveRelease>>(
            """
            [{"tag_name":"gpt-live-debug-1","target_commitish":"master","draft":true,
              "published_at":null,"assets":[],"prerelease":true}]
            """.trimIndent()
        )
        assertTrue(releases.single().draft)
        assertNull(releases.single().publishedAt)
        val parsed = json.decodeFromString<GptLiveUpdateManifest>(
            """
            {"schemaVersion":1,"branch":"gpt-live","releaseTag":"$tag","sourceCommit":"$commit",
             "version":"3.1.1-gpt-live.1","versionCode":200,"applicationId":"$packageName",
             "signingCertificateSha256":"${manifest.signingCertificateSha256}","changelog":"更新",
             "downloads":[{"abi":"arm64-v8a","name":"$name","url":"$url","sha256":"$sha256","size":21000000}]}
            """.trimIndent()
        )
        assertNotNull(parsed.toUpdateInfo(release, listOf("arm64-v8a"), packageName))
    }

    @Test
    fun `malformed or modified manifest assets are skipped`() {
        val bytes = Json.encodeToString(manifest).toByteArray()
        assertEquals(manifest, decodeGptLiveUpdateManifest(bytes, digest(bytes)))
        assertNull(decodeGptLiveUpdateManifest(bytes, null))
        assertNull(decodeGptLiveUpdateManifest(bytes, "sha256:${"0".repeat(64)}"))
        val malformed = "{\"schemaVersion\":1}".toByteArray()
        assertNull(decodeGptLiveUpdateManifest(malformed, digest(malformed)))
    }

    private fun withDownload(download: GptLiveManifestDownload): UpdateInfo? =
        manifest.copy(downloads = listOf(download)).toUpdateInfo(release, listOf("arm64-v8a"), packageName)

    private fun digest(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
