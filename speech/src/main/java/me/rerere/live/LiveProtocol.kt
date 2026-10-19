package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal sealed interface LiveServerMessage {
    data class Event(val value: LiveEvent) : LiveServerMessage

    data class Acknowledgment(val type: String, val clientEventId: String?) : LiveServerMessage
}

/** Wire types for GPT Live, deliberately separate from the Realtime ASR implementation. */
internal object LiveProtocol {
    private const val MAX_MESSAGE_BYTES = 262_144
    private const val HISTORY_BUDGET_BYTES = 6_000
    private const val HISTORY_MESSAGE_OVERHEAD = 32
    private const val HISTORY_MAX_MESSAGES = 128
    private val json = Json { ignoreUnknownKeys = true }

    fun start(config: LiveSessionConfig, eventId: String): String {
        require(config.model.isNotBlank() && config.model.length <= 128) { "A Live model is required" }
        require(config.voice.isNotBlank() && config.voice.length <= 128) { "A Live voice is required" }
        require(config.instructions.toByteArray(Charsets.UTF_8).size <= 12_000) {
            "Live conversation instructions are too long"
        }
        val history = boundedHistory(config.history)
        return buildJsonObject {
            put("type", "session.start")
            put("event_id", eventId)
            put("session", buildJsonObject {
                put("model", config.model)
                put("instructions", config.instructions)
                put("audio", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "audio/pcm")
                        put("rate", 24000)
                    })
                    put("output", buildJsonObject { put("voice", config.voice) })
                })
                put("delegation", buildJsonObject { put("type", "client") })
                put("store", false)
                put("input", buildJsonArray {
                    history.forEach { message ->
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", message.role)
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", if (message.role == "assistant") "output_text" else "input_text")
                                    put("text", message.text)
                                })
                            })
                        })
                    }
                })
            })
        }.toString()
    }

    /** Keep recent text with headroom under the 8,192-token / 128-message API limit. */
    internal fun boundedHistory(history: List<LiveHistoryMessage>): List<LiveHistoryMessage> {
        require(history.all { it.role in setOf("developer", "user", "assistant") }) {
            "Live history roles must be developer, user, or assistant"
        }
        val selected = mutableListOf<LiveHistoryMessage>()
        var remaining = HISTORY_BUDGET_BYTES
        for (message in history.asReversed()) {
            if (selected.size == HISTORY_MAX_MESSAGES || remaining <= HISTORY_MESSAGE_OVERHEAD) break
            if (message.text.isBlank()) continue
            val allowance = remaining - HISTORY_MESSAGE_OVERHEAD
            val text = utf8Suffix(message.text, allowance)
            if (text.isEmpty()) break
            selected += message.copy(text = text)
            remaining -= text.toByteArray(Charsets.UTF_8).size + HISTORY_MESSAGE_OVERHEAD
        }
        return selected.asReversed()
    }

    private fun utf8Suffix(text: String, budget: Int): String {
        var offset = text.length
        var size = 0
        while (offset > 0) {
            val previous = offset - Character.charCount(text.codePointBefore(offset))
            val nextSize = text.substring(previous, offset).toByteArray(Charsets.UTF_8).size
            if (size + nextSize > budget) break
            size += nextSize
            offset = previous
        }
        return text.substring(offset)
    }

    fun audio(pcm: ByteArray): String = buildJsonObject {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "Live PCM audio requires complete 16-bit samples" }
        put("type", "session.input_audio.append")
        put("audio", pcm.toByteString().base64())
    }.toString()

    fun command(type: String, eventId: String): String = buildJsonObject {
        put("type", type)
        put("event_id", eventId)
    }.toString()

    fun append(type: String, eventId: String, delegationId: String?, content: String): String = buildJsonObject {
        put("type", type)
        put("event_id", eventId)
        put("delegation_id", delegationId?.let(::JsonPrimitive) ?: JsonNull)
        put("content", content)
    }.toString()

    /** Unknown event types are ignored; malformed known events fail without including the raw payload. */
    fun parse(text: String): LiveServerMessage? {
        require(text.length <= MAX_MESSAGE_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Live server event is too large"
        }
        val body = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Expected a Live event object")
        val type = body.requiredString("type")
        val event = when (type) {
            "session.started" -> LiveEvent.Started(body.requiredObject("session").requiredString("id"))
            "session.output_audio.delta" -> {
                val audio = body.requiredString("delta").decodeBase64()?.toByteArray()
                    ?: throw IllegalArgumentException("Invalid Live audio encoding")
                require(audio.size % 2 == 0) { "Invalid Live PCM sample alignment" }
                LiveEvent.Audio(audio)
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> LiveEvent.Transcript(
                role = if (type == "session.input_transcript.delta") "user" else "assistant",
                delta = body.requiredString("delta"),
                eventId = body.optionalString("event_id"),
                startMs = body.finiteNumber("start_ms"),
                endMs = body.finiteNumber("end_ms"),
            )
            "session.delegation.created" -> {
                val delegation = body.requiredObject("delegation")
                if (delegation.optionalString("target") != "client") return null
                LiveEvent.Delegation(delegation.requiredString("id"), body.finiteNumber("offset_ms"))
            }
            "session.usage.updated" -> LiveEvent.Usage(body.requiredObject("usage").duration())
            "session.closed" -> LiveEvent.Closed(
                reason = body.requiredString("reason"),
                seconds = (body["usage"] as? JsonObject)?.duration(),
            )
            "error" -> {
                val error = body.requiredObject("error")
                LiveEvent.Error(
                    message = error.optionalString("message") ?: "GPT Live rejected a command",
                    fatal = false,
                    clientEventId = error.optionalString("client_event_id") ?: body.optionalString("client_event_id"),
                )
            }
            "session.input_audio.muted", "session.input_audio.unmuted",
            "session.commentary.appended", "session.thinking.appended", "session.instructions.appended" -> {
                return LiveServerMessage.Acknowledgment(type, body.optionalString("client_event_id"))
            }
            else -> return null
        }
        return LiveServerMessage.Event(event)
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name] as? JsonObject ?: throw IllegalArgumentException("Missing Live object field")

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw IllegalArgumentException("Missing Live text field")

    private fun JsonObject.finiteNumber(name: String): Double {
        val value = (this[name] as? JsonPrimitive)?.doubleOrNull
            ?: throw IllegalArgumentException("Missing Live numeric field")
        require(value.isFinite()) { "Invalid Live numeric value" }
        return value
    }

    private fun JsonObject.duration(): Double = finiteNumber("seconds").also {
        require(it >= 0) { "Invalid Live duration" }
    }
}
