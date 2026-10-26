package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Google Cloud BidiGenerateContent wire protocol, independent of the HTTP Gemini chat API. */
internal object GeminiLiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val FRAME_BYTES = 640 // 20 ms of mono PCM16, little endian.
    private const val MAX_EVENT_LENGTH = 512_000
    private const val MAX_AUDIO_LENGTH = 320_000

    fun setup(config: LiveSessionConfig): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", config.model)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
            }
            if (config.instructions.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", config.instructions) })
                    }
                }
            }
            putJsonObject("inputAudioTranscription") { }
            putJsonObject("outputAudioTranscription") { }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") { } }
        }
    }.toString()

    fun context(context: List<String>): String? {
        val history = context.takeLast(24).filter { it.isNotBlank() }.joinToString("\n").take(24_000)
        if (history.isBlank()) return null
        return buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", "Earlier conversation for reference:\n$history")
                            })
                        }
                    })
                }
                // Add context without prompting an unsolicited spoken response.
                put("turnComplete", false)
            }
        }.toString()
    }

    fun audio(frame: ByteArray): String {
        require(frame.size == FRAME_BYTES) { "Gemini Live requires 20 ms PCM16 frames at 16 kHz" }
        return buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", Base64.getEncoder().encodeToString(frame))
                }
            }
        }.toString()
    }

    fun audioStreamEnd(): String = "{\"realtimeInput\":{\"audioStreamEnd\":true}}"

    fun parse(text: String): List<GeminiServerEvent> {
        require(text.length <= MAX_EVENT_LENGTH) { "The Gemini Live server sent an oversized event" }
        val message = Json.parseToJsonElement(text) as? JsonObject
            ?: error("The Gemini Live server sent an invalid event")
        return buildList {
            if (message["setupComplete"] is JsonObject) add(GeminiServerEvent.Ready)
            (message["error"] as? JsonObject)?.let { error ->
                add(GeminiServerEvent.Error(
                    listOfNotNull(
                        error.text("message"),
                        error.text("status"),
                        error.text("code")?.let { "code: $it" },
                    ).joinToString("; ").ifBlank { "Google Cloud returned an unspecified error" }
                ))
            }
            (message["goAway"] as? JsonObject)?.let {
                add(GeminiServerEvent.GoAway(durationMs(it.text("timeLeft")) ?: 0))
            }
            (message["serverContent"] as? JsonObject)?.let { content ->
                val audio = mutableListOf<ByteArray>()
                val modelTurn = content["modelTurn"] as? JsonObject
                val parts = modelTurn?.get("parts") as? JsonArray
                parts?.forEach { part ->
                    val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject
                    val mime = inline?.text("mimeType") ?: return@forEach
                    if (!mime.startsWith("audio/pcm")) return@forEach
                    val rate = Regex("(?:^|;)\\s*rate=(\\d+)").find(mime)?.groupValues?.get(1)
                    require(rate == null || rate == OUTPUT_SAMPLE_RATE.toString()) {
                        "The Gemini Live server returned an unsupported audio sample rate"
                    }
                    val encoded = inline.text("data") ?: error("Missing Gemini Live audio data")
                    require(encoded.length <= MAX_AUDIO_LENGTH) { "Gemini Live audio chunk is too large" }
                    val pcm = Base64.getDecoder().decode(encoded)
                    require(pcm.size % 2 == 0) { "The Gemini Live server sent invalid PCM16 audio" }
                    audio.add(pcm)
                }
                add(GeminiServerEvent.Content(
                    audio = audio,
                    input = transcription(content["inputTranscription"] as? JsonObject),
                    output = transcription(content["outputTranscription"] as? JsonObject),
                    interrupted = (content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true,
                ))
            }
        }
    }

    private fun transcription(value: JsonObject?): GeminiTranscription? {
        val text = value?.text("text")?.takeIf { it.isNotEmpty() } ?: return null
        return GeminiTranscription(text, durationMs(value.text("startOffset")), durationMs(value.text("endOffset")))
    }

    private fun durationMs(value: String?): Long? = value?.removeSuffix("s")?.toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 && it <= 86_400.toBigDecimal() }
        ?.multiply(1_000.toBigDecimal())?.toLong()

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
}

internal data class GeminiTranscription(val text: String, val startMs: Long?, val endMs: Long?)

internal sealed interface GeminiServerEvent {
    data object Ready : GeminiServerEvent
    data class Content(
        val audio: List<ByteArray>,
        val input: GeminiTranscription?,
        val output: GeminiTranscription?,
        val interrupted: Boolean,
    ) : GeminiServerEvent
    data class GoAway(val timeLeftMs: Long) : GeminiServerEvent
    data class Error(val message: String) : GeminiServerEvent
}
