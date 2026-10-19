package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI

/** Keeps server diagnostics useful without echoing credentials or an entire proxy error page. */
internal object LiveHandshakeDiagnostics {
    const val MAX_BODY_BYTES = 8_192L
    private const val MAX_MESSAGE_LENGTH = 1_200
    private val credentialNames = setOf("authorization", "proxy-authorization", "cookie", "x-api-key")
    private val requestIdNames = setOf("x-request-id", "x-vercel-id")
    private val requestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,127}")
    private val htmlPattern = Regex("<\\s*(?:!doctype|/?[a-z][a-z0-9]*)\\b", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("(?i)\\b(?:https?|wss?)://[^\\s<>\"']+")
    private val authorizationPattern = Regex("(?i)\\b(?:Bearer|Basic)\\s+[^\\s,;\"'<>]+")
    private val cookiePattern = Regex("(?im)\\b(?:cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*")
    private val namedSecretPattern = Regex(
        "(?i)([\"']?(?:authorization|proxy-authorization|x-api-key|api[-_ ]?key|" +
            "access[-_ ]?token|token|secret|password)[\"']?\\s*[:=]\\s*)" +
            "(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    )
    private val keyPattern = Regex("(?i)\\b(?:sk[-_]|vck_|vc_)[a-z0-9_-]{6,}")
    private val jwtPattern = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?")

    fun failure(
        config: LiveSessionConfig,
        status: Int?,
        host: String?,
        responseHeaders: Map<String, String> = emptyMap(),
        failureMessage: String?,
        readBody: (() -> String)? = null,
    ): IOException {
        val body = runCatching { readBody?.invoke()?.take(MAX_BODY_BYTES.toInt()) }.getOrNull()
        val detail = body?.takeIf { it.isNotBlank() }?.let(::serverDetail)
            ?: failureMessage?.takeIf { it.isNotBlank() }
            ?: "The connection could not be established."
        val safeHost = host?.let { sanitize(config, it, 253) }
            ?.takeIf { Regex("[A-Za-z0-9.:-]{1,253}").matches(it) }
        val ids = responseHeaders.entries.mapNotNull { (name, value) ->
            if (name.lowercase() !in requestIdNames) return@mapNotNull null
            val safe = sanitize(config, value, 128)
            if (!requestIdPattern.matches(safe)) return@mapNotNull null
            "${name.lowercase()}: $safe"
        }.take(2)
        val message = buildString {
            append("Live connection failed")
            status?.let { append(" (HTTP $it)") }
            safeHost?.let { append(" [$it]") }
            append(": ")
            append(sanitize(config, detail, 800))
            if (ids.isNotEmpty()) append("\n${ids.joinToString("; ")}")
        }.take(MAX_MESSAGE_LENGTH)
        // The transport exception and body-read exception may themselves contain credentials.
        return IOException(message)
    }

    internal fun sanitize(config: LiveSessionConfig, value: String, maxLength: Int = 800): String {
        val secrets = buildSet {
            if (config.apiKey.isNotEmpty()) add(config.apiKey)
            config.headers.forEach { (name, content) ->
                if (name.lowercase() !in credentialNames || content.isBlank()) return@forEach
                add(content)
                if (name.equals("cookie", true)) {
                    content.split(';').forEach { part ->
                        part.substringAfter('=', "").trim()
                            .removeSurrounding("\"").removeSurrounding("'")
                            .takeIf { it.isNotEmpty() }?.let(::add)
                    }
                } else {
                    content.substringAfter(' ', content).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.sortedByDescending { it.length }
        var safe = urlPattern.replace(value) { match ->
            runCatching { URI(match.value).host }.getOrNull() ?: "[URL]"
        }
        secrets.forEach { secret ->
            safe = safe.replace(secret, "[redacted]")
            // A bounded body may end in the middle of a reflected key.
            val prefixLength = (minOf(secret.length - 1, safe.length) downTo 8)
                .firstOrNull { safe.endsWith(secret.take(it)) }
            if (prefixLength != null) safe = safe.dropLast(prefixLength) + "[redacted]"
        }
        safe = cookiePattern.replace(safe, "Cookie: [redacted]")
        safe = authorizationPattern.replace(safe, "[redacted authorization]")
        safe = namedSecretPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
        safe = keyPattern.replace(safe, "[redacted]")
        safe = jwtPattern.replace(safe, "[redacted]")
        if (htmlPattern.containsMatchIn(safe)) return "The server returned an HTML error page."
        return safe.map { character ->
            if (character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()) {
                ' '
            } else {
                character
            }
        }.joinToString("").replace(Regex("\\s+"), " ").trim().take(maxLength)
    }

    private fun serverDetail(body: String): String {
        val trimmed = body.trim().trimStart('\uFEFF')
        if (htmlPattern.containsMatchIn(trimmed) && !trimmed.startsWith('{')) {
            return "The server returned an HTML error page."
        }
        val json = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        if (json != null) {
            val error = json["error"]
            val fields = error as? JsonObject ?: json
            val message = (fields["message"] as? JsonPrimitive)?.contentOrNull
                ?: (error as? JsonPrimitive)?.contentOrNull
            val metadata = listOf("type", "code").mapNotNull { name ->
                (fields[name] as? JsonPrimitive)?.contentOrNull?.let { "$name: $it" }
            }
            return (listOfNotNull(message) + metadata).joinToString("; ")
                .ifBlank { "The server returned an error without further details." }
        }
        return if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            "The server returned an unreadable error response."
        } else {
            trimmed
        }
    }
}
