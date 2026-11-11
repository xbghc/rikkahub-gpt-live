package me.rerere.rikkahub.utils

import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateApkIdentityTest {
    private val installed = UpdateApkIdentity("me.rerere.rikkahub.debug", 199, setOf("published-certificate"))
    private val archive = installed.copy(versionCode = 200)
    private val download = UpdateDownload(
        name = "app-github-arm64-v8a-debug.apk",
        url = "https://github.com/xbghc/rikkahub-gpt-live/releases/download/gpt-live-debug-1/app.apk",
        size = "25 MB",
        sha256 = "a".repeat(64),
        versionCode = 200,
        applicationId = installed.packageName,
        bytes = 25_000_000,
    )

    @Test
    fun `accepts a newer update with the installed package and certificate`() {
        validateUpdateApkIdentity(download, archive, installed)
    }

    @Test
    fun `rejects an upstream APK with a different package name`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApkIdentity(download, archive.copy(packageName = "me.rerere.rikkahub"), installed)
        }
    }

    @Test
    fun `rejects a changed signing key even for the expected version`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApkIdentity(download, archive.copy(signerDigests = setOf("replacement-key")), installed)
        }
    }

    @Test
    fun `rejects unsigned APKs`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApkIdentity(download, archive.copy(signerDigests = emptySet()), installed)
        }
    }

    @Test
    fun `rejects a downgrade and mismatched release metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApkIdentity(download.copy(versionCode = 198), archive.copy(versionCode = 198), installed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateApkIdentity(download, archive.copy(versionCode = 201), installed)
        }
    }
}
