package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GptLiveProtocolTest {
    @Test
    fun backendResultsPreserveDelegationIdUnicodeAndByteLimits() {
        val text = "结果😀".repeat(350)
        val messages = GptLiveProtocol.delegationResult("task-1", text).map {
            Json.parseToJsonElement(it).jsonObject
        }
        assertEquals(text, messages.joinToString("") { it.getValue("content").jsonPrimitive.content })
        messages.forEach {
            assertEquals("task-1", it.getValue("delegation_id").jsonPrimitive.content)
            assertEquals("session.commentary.append", it.getValue("type").jsonPrimitive.content)
            assertTrue(it.getValue("content").jsonPrimitive.content.toByteArray(Charsets.UTF_8).size <= 400)
        }
        val longResult = GptLiveProtocol.delegationResult("task-2", "x".repeat(6401))
        assertEquals(17, longResult.size)
        assertTrue(longResult.last().contains("shortened"))
    }

    @Test
    fun delegationTimingAndTranscriptIdentityReachTheBackend() {
        val event = GptLiveProtocol.parse(
            """{"type":"session.delegation.created","offset_ms":1200.5,"delegation":{"id":"d1","target":"client"}}"""
        ) as ServerEvent.Delegation
        assertEquals(1200.5, event.offsetMs, 0.0)
        val transcript = GptLiveProtocol.parse(
            """{"type":"session.input_transcript.delta","event_id":"caption-1","delta":"Hello"}"""
        ) as ServerEvent.Transcript
        assertEquals("caption-1", transcript.value.eventId)
    }

    @Test
    fun startsNativeLiveWithClientDelegationAndPcmAudio() {
        val config = LiveSessionConfig(
            "wss://ai-gateway.vercel.sh/v1/live/sessions",
            "secret",
            "openai/gpt-live-1",
            "Be concise",
        )
        val event = Json.parseToJsonElement(GptLiveProtocol.start(config)).jsonObject
        val session = event.getValue("session").jsonObject
        val audio = session.getValue("audio").jsonObject
        assertEquals("session.start", event.getValue("type").jsonPrimitive.content)
        assertEquals("openai/gpt-live-1", session.getValue("model").jsonPrimitive.content)
        assertFalse(session.getValue("store").jsonPrimitive.boolean)
        assertEquals("client", session.getValue("delegation").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(24_000, audio.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
        assertEquals("audio/pcm", audio.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("marin", audio.getValue("output").jsonObject.getValue("voice").jsonPrimitive.content)
        assertEquals("Be concise", session.getValue("instructions").jsonPrimitive.content)
        assertFalse(event.toString().contains("secret"))
    }

    @Test
    fun encodesTwentyMillisecondsWithoutAddingAWavHeader() {
        val pcm = ByteArray(960) { (it % 128).toByte() }
        val event = Json.parseToJsonElement(GptLiveProtocol.audio(pcm)).jsonObject
        assertEquals("session.input_audio.append", event.getValue("type").jsonPrimitive.content)
        assertArrayEquals(pcm, Base64.getDecoder().decode(event.getValue("audio").jsonPrimitive.content))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncorrectInputFrameDuration() {
        GptLiveProtocol.audio(ByteArray(480))
    }

    @Test
    fun parsesAudioAndBothTimestampedTranscriptStreams() {
        val audio = GptLiveProtocol.parse("""{"type":"session.output_audio.delta","delta":"AQACAA=="}""")
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), (audio as ServerEvent.Audio).pcm)
        for ((stream, speaker) in listOf("input" to LiveSpeaker.User, "output" to LiveSpeaker.Assistant)) {
            val parsed = GptLiveProtocol.parse(
                """{"type":"session.${stream}_transcript.delta","delta":"你好","start_ms":40,"end_ms":180}"""
            ) as ServerEvent.Transcript
            assertEquals(LiveTranscript(speaker, "你好", 40, 180), parsed.value)
        }
    }

    @Test
    fun returnsHonestDelegationResultWithTheOriginalId() {
        val parsed = GptLiveProtocol.parse(
            """{"type":"session.delegation.created","delegation":{"id":"delegate-2"}}"""
        ) as ServerEvent.Delegation
        val reply = Json.parseToJsonElement(GptLiveProtocol.unavailableDelegation(parsed.id)).jsonObject
        assertEquals("session.commentary.append", reply.getValue("type").jsonPrimitive.content)
        assertEquals("delegate-2", reply.getValue("delegation_id").jsonPrimitive.content)
        assertTrue(reply.getValue("content").jsonPrimitive.content.contains("No external action"))
    }

    @Test
    fun sessionContextIsBoundedAndDoesNotSplitUnicodeCharacters() {
        val input = "语音😀".repeat(200)
        val event = Json.parseToJsonElement(GptLiveProtocol.context(input)).jsonObject
        val content = event.getValue("content").jsonPrimitive.content
        assertEquals(JsonNull, event["delegation_id"])
        assertEquals("session.thinking.append", event.getValue("type").jsonPrimitive.content)
        assertTrue(content.toByteArray(Charsets.UTF_8).size <= 400)
        assertTrue(input.startsWith(content))
        assertFalse(Character.isHighSurrogate(content.last()))
    }

    @Test
    fun handlesLifecycleErrorsAndUnknownEvents() {
        assertEquals(ServerEvent.Started, GptLiveProtocol.parse("""{"type":"session.started"}"""))
        assertEquals(ServerEvent.Closed, GptLiveProtocol.parse("""{"type":"session.closed"}"""))
        assertEquals(ServerEvent.Ignored, GptLiveProtocol.parse("""{"type":"session.usage.updated"}"""))
        assertEquals(
            ServerEvent.Error("No credits"),
            GptLiveProtocol.parse("""{"type":"error","error":{"message":"No credits"}}"""),
        )
        assertEquals(
            "session.close",
            Json.parseToJsonElement(GptLiveProtocol.close()).jsonObject.getValue("type").jsonPrimitive.content
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAudioWithAnIncompletePcmSample() {
        GptLiveProtocol.parse("""{"type":"session.output_audio.delta","delta":"AQ=="}""")
    }

    @Test
    fun preservesSessionErrorCodesWhenTheConnectionAlreadyOpened() {
        val event = GptLiveProtocol.parse(
            """
                {"type":"error","error":{
                    "message":"Request denied",
                    "type":"forbidden",
                    "code":"routing_rule_denied",
                    "private_field":"not displayed"
                }}
            """.trimIndent()
        ) as ServerEvent.Error
        assertEquals(
            "Request denied; type: forbidden; code: routing_rule_denied",
            event.details(),
        )
        assertFalse(event.details().contains("not displayed"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedAudioEvents() {
        GptLiveProtocol.parse("""{"type":"session.output_audio.delta","delta":"${"A".repeat(128_004)}"}""")
    }
}
