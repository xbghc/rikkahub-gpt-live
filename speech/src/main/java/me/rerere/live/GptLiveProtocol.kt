package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

internal object GptLiveProtocol {
    const val SAMPLE_RATE = 24_000
    const val FRAME_BYTES = 960 // 20 ms of mono, little-endian PCM16.
    private const val MAX_AUDIO_BASE64_LENGTH = 128_000

    fun start(config: LiveSessionConfig): String = buildJsonObject {
        put("type", "session.start")
        putJsonObject("session") {
            put("model", config.model)
            put("store", false)
            putJsonObject("delegation") { put("type", "client") }
            putJsonObject("audio") {
                putJsonObject("format") {
                    put("type", "audio/pcm")
                    put("rate", SAMPLE_RATE)
                }
                putJsonObject("output") { put("voice", "marin") }
            }
            put("instructions", config.instructions)
        }
    }.toString()

    fun audio(frame: ByteArray): String {
        require(frame.size == FRAME_BYTES) { "Live audio requires a 20 ms PCM16 frame" }
        return buildJsonObject {
            put("type", "session.input_audio.append")
            put("audio", Base64.getEncoder().encodeToString(frame))
        }.toString()
    }

    fun context(content: String): String = buildJsonObject {
        put("type", "session.thinking.append")
        put("delegation_id", JsonNull)
        // A byte limit is conservative even for text with many multibyte characters.
        put("content", limitedContext(content))
    }.toString()

    fun unavailableDelegation(id: String): String = buildJsonObject {
        put("type", "session.commentary.append")
        put("delegation_id", id)
        put(
            "content",
            "This live conversation has no backend tools configured. No external action was " +
                "performed or information retrieved. Tell the user this limitation when relevant.",
        )
    }.toString()

    /** Split by UTF-8 bytes without splitting a code point; bound large assistant/tool results. */
    fun delegationResult(id: String, text: String): List<String> {
        val chunks = mutableListOf<String>()
        val points = text.codePoints().iterator()
        val chunk = StringBuilder()
        var bytes = 0
        var shortened = false
        while (points.hasNext()) {
            val value = String(Character.toChars(points.nextInt()))
            val size = value.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 400) {
                chunks.add(chunk.toString())
                chunk.clear()
                bytes = 0
                if (chunks.size == 16) {
                    shortened = true
                    break
                }
            }
            chunk.append(value)
            bytes += size
        }
        if (chunk.isNotEmpty()) chunks.add(chunk.toString())
        if (shortened) chunks.add("The full answer is available in the chat. This spoken result was shortened.")
        return chunks.map { content ->
            buildJsonObject {
                put("type", "session.commentary.append")
                put("delegation_id", id)
                put("content", content)
            }.toString()
        }
    }

    fun close(): String = "{\"type\":\"session.close\"}"

    fun parse(text: String): ServerEvent {
        require(text.length <= 512_000) { "The Live server sent an oversized event" }
        val event = Json.parseToJsonElement(text).jsonObject
        return when (event.string("type")) {
            "session.started" -> ServerEvent.Started
            "session.closed" -> ServerEvent.Closed
            "session.output_audio.delta" -> {
                val encoded = event.string("delta")
                require(encoded.length <= MAX_AUDIO_BASE64_LENGTH) {
                    "The Live server sent an oversized audio chunk"
                }
                val pcm = Base64.getDecoder().decode(encoded)
                require(pcm.size % 2 == 0) { "The Live server sent invalid PCM16 audio" }
                ServerEvent.Audio(pcm)
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                ServerEvent.Transcript(
                    LiveTranscript(
                        speaker = if (event.string("type") == "session.input_transcript.delta") {
                            LiveSpeaker.User
                        } else {
                            LiveSpeaker.Assistant
                        },
                        text = event.string("delta"),
                        startMs = event["start_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                        endMs = event["end_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                        eventId = event["event_id"]?.jsonPrimitive?.contentOrNull,
                    )
                )
            }
            "session.delegation.created" -> ServerEvent.Delegation(
                event["delegation"]?.jsonObject?.string("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: error("The Live server sent a delegation without an ID"),
                event["offset_ms"]?.jsonPrimitive?.doubleOrNull
                    ?.takeIf { it.isFinite() } ?: 0.0,
            )
            "error" -> {
                val details = event["error"] as? JsonObject
                ServerEvent.Error(
                    message = details?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "The Live server returned an error",
                    type = details?.get("type")?.jsonPrimitive?.contentOrNull,
                    code = details?.get("code")?.jsonPrimitive?.contentOrNull,
                )
            }
            else -> ServerEvent.Ignored
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull ?: error("Missing Live event field: $key")

    internal fun limitedContext(content: String): String {
        var bytes = 0
        return buildString {
            val points = content.codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                val encoded = String(Character.toChars(point))
                bytes += encoded.toByteArray(Charsets.UTF_8).size
                if (bytes > 400) break
                append(encoded)
            }
        }
    }
}

internal sealed interface ServerEvent {
    data object Started : ServerEvent
    data object Closed : ServerEvent
    data object Ignored : ServerEvent
    data class Audio(val pcm: ByteArray) : ServerEvent
    data class Transcript(val value: LiveTranscript) : ServerEvent
    data class Delegation(val id: String, val offsetMs: Double = 0.0) : ServerEvent
    data class Error(val message: String, val type: String? = null, val code: String? = null) : ServerEvent {
        fun details(): String = listOfNotNull(
            message,
            type?.let { "type: $it" },
            code?.let { "code: $it" },
        ).joinToString("; ")
    }
}
