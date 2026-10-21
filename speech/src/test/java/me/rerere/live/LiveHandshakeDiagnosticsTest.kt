package me.rerere.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LiveHandshakeDiagnosticsTest {
    private val config = LiveSessionConfig(
        url = "wss://ai-gateway.vercel.sh/v1/live/sessions",
        apiKey = "configured-gateway-secret",
        model = "openai/gpt-live-1",
    )

    @Test
    fun forbiddenIncludesServerReasonAndWhitelistedRequestIds() {
        val error = failure(
            body = """
                {"error":{
                    "message":"Live access is not enabled",
                    "type":"access_error",
                    "code":"live_access_denied"
                }}
            """.trimIndent(),
            headers = mapOf("X-Request-ID" to "req-123", "x-vercel-id" to "sfo1::abc-42", "set-cookie" to "secret"),
        )
        val text = error.message.orEmpty()
        assertTrue(text.contains("HTTP 403"))
        assertTrue(text.contains("[ai-gateway.vercel.sh]"))
        assertTrue(text.contains("Live access is not enabled"))
        assertTrue(text.contains("type: access_error"))
        assertTrue(text.contains("code: live_access_denied"))
        assertTrue(text.contains("x-request-id: req-123"))
        assertTrue(text.contains("x-vercel-id: sfo1::abc-42"))
        assertFalse(text.contains("set-cookie"))
        assertNull(error.cause)
    }

    @Test
    fun unauthorizedIncludesAReasonWithoutEchoingTheKey() {
        val text = failure(
            body = """{"error":{"message":"Invalid API key: ${config.apiKey}","code":"invalid_api_key"}}""",
            status = 401,
        ).message.orEmpty()
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("invalid_api_key"))
        assertFalse(text.contains(config.apiKey))
    }

    @Test
    fun htmlIsReportedWithoutDisplayingThePage() {
        val text = failure(
            "<html><body>SECRET-DIAGNOSTICS<script>doSomething()</script></body></html>"
        ).message.orEmpty()
        assertTrue(text.contains("HTML error page"))
        assertFalse(text.contains("SECRET-DIAGNOSTICS"))
        assertFalse(text.contains("<"))
    }

    @Test
    fun malformedJsonNeverBecomesAPlainTextDump() {
        val text = failure("""{"error":{"message":"truncated", "private_field":"UNRELATED-SECRET"""").message.orEmpty()
        assertTrue(text.contains("unreadable error response"))
        assertFalse(text.contains("UNRELATED-SECRET"))
    }

    @Test
    fun oversizedJsonIsBoundedBeforeParsingAndNeverDumped() {
        val text = failure("""{"private_field":"${"x".repeat(20_000)}","error":"late"}""").message.orEmpty()
        assertTrue(text.contains("unreadable error response"))
        assertTrue(text.length <= 1_200)
        assertFalse(text.contains("private_field"))
    }

    @Test
    fun shortPlainTextReasonIsAvailableAndLongTextIsBounded() {
        assertTrue(failure("Forbidden: account access denied.").message.orEmpty().contains("account access denied"))
        assertTrue(failure("plain error ".repeat(3_000)).message.orEmpty().length <= 1_200)
    }

    @Test
    fun missingResponseRetainsSanitizedTransportFailure() {
        val error = LiveHandshakeDiagnostics.failure(
            config = config,
            status = null,
            host = "ai-gateway.vercel.sh",
            failureMessage = "Cannot connect to https://user:${config.apiKey}" +
                "@example.test/private/path?token=another-secret",
        )
        assertTrue(error.message.orEmpty().contains("Cannot connect to example.test"))
        assertFalse(error.message.orEmpty().contains("HTTP"))
        assertFalse(error.message.orEmpty().contains("private/path"))
        assertFalse(error.message.orEmpty().contains("another-secret"))
        assertNull(error.cause)
    }

    @Test
    fun failedBodyReadPreservesOriginalFailureAndDoesNotRetainTheReadException() {
        val error = LiveHandshakeDiagnostics.failure(
            config = config,
            status = 403,
            host = "ai-gateway.vercel.sh",
            failureMessage = "Expected HTTP 101 but received 403",
            readBody = { throw IOException("Read failed for ${config.apiKey}") },
        )
        assertTrue(error.message.orEmpty().contains("Expected HTTP 101 but received 403"))
        assertFalse(error.message.orEmpty().contains(config.apiKey))
        assertNull(error.cause)
    }

    @Test
    fun scrubsCredentialHeadersCaseInsensitivelyAndKnownTokenFormats() {
        val privateConfig = config.copy(headers = mapOf(
            "aUtHoRiZaTiOn" to "Bearer custom-auth-value",
            "PROXY-AUTHORIZATION" to "Basic proxy-credential-value",
            "Cookie" to "session=session-value; other=other-cookie-value",
            "X-API-KEY" to "custom-api-value",
        ))
        val secrets = listOf(
            config.apiKey, "custom-auth-value", "proxy-credential-value", "session-value", "other-cookie-value",
            "custom-api-value", "unknown-bearer-value", "unknown-api-value", "sk-anothersecret123",
            "vck_othersecret123", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature",
        )
        val text = LiveHandshakeDiagnostics.sanitize(
            privateConfig,
            "${secrets.take(6).joinToString(" ")} Bearer unknown-bearer-value api_key=unknown-api-value " +
                secrets.takeLast(3).joinToString(" "),
        )
        secrets.forEach { assertFalse("Must redact $it", text.contains(it)) }
        assertTrue(text.contains("redacted"))
    }

    @Test
    fun requestIdsCannotSmuggleCredentialsOrControlCharacters() {
        val text = failure(
            body = "Forbidden",
            headers = mapOf(
                "x-request-id" to config.apiKey,
                "x-vercel-id" to "safe\r\nAuthorization: Bearer injected-secret",
                "random-header" to "UNRELATED-SECRET",
            ),
        ).message.orEmpty()
        assertFalse(text.contains(config.apiKey))
        assertFalse(text.contains("injected-secret"))
        assertFalse(text.contains("UNRELATED-SECRET"))
        assertFalse(text.contains("x-vercel-id"))
    }

    @Test
    fun structuredErrorsExposeOnlyRecognizedFields() {
        val text = failure(
            """{"error":{"message":"No permission","private_field":"UNRELATED-SECRET"},"request":{"token":"OTHER-SECRET"}}"""
        )
            .message.orEmpty()
        assertTrue(text.contains("No permission"))
        assertFalse(text.contains("UNRELATED-SECRET"))
        assertFalse(text.contains("OTHER-SECRET"))
        assertTrue(failure("""{"error":"Forbidden"}""").message.orEmpty().contains("Forbidden"))
    }

    @Test
    fun partialReflectedKeyAtTheReadBoundaryIsRedacted() {
        val prefix = config.apiKey.take(16)
        val text = LiveHandshakeDiagnostics.sanitize(config, "Rejected credential $prefix")
        assertFalse(text.contains(prefix))
        assertTrue(text.endsWith("[redacted]"))
    }

    @Test
    fun quotedCookieValuesAndJsonByteOrderMarksDoNotBypassFiltering() {
        val privateConfig = config.copy(headers = mapOf("cookie" to "session=\"quoted-cookie-secret\""))
        assertFalse(
            LiveHandshakeDiagnostics.sanitize(privateConfig, "Rejected quoted-cookie-secret")
                .contains("quoted-cookie-secret")
        )
        assertFalse(
            failure("\uFEFF{\"private_field\":\"UNRELATED-SECRET").message.orEmpty().contains("UNRELATED-SECRET")
        )
    }

    @Test
    fun scrubRemovesControlAndBidiCharacters() {
        assertEquals("access denied", LiveHandshakeDiagnostics.sanitize(config, "access\u0000\u202E denied"))
    }

    private fun failure(
        body: String,
        status: Int = 403,
        headers: Map<String, String> = emptyMap(),
    ): IOException = LiveHandshakeDiagnostics.failure(
        config = config,
        status = status,
        host = "ai-gateway.vercel.sh",
        responseHeaders = headers,
        failureMessage = "Expected HTTP 101",
        readBody = { body },
    )
}
