package me.rerere.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LiveProtocolTest {
    @Test
    fun `startup uses native Live client delegation and role-correct text history`() {
        val config = LiveSessionConfig(
            apiKey = "sk-never-serialize-this",
            instructions = "Delegate substantive questions to the existing assistant.",
            history = listOf(
                LiveHistoryMessage("developer", "Trusted context"),
                LiveHistoryMessage("user", "Hello"),
                LiveHistoryMessage("assistant", "Hi"),
            ),
        )
        val wire = LiveProtocol.start(config, "start-1")
        val root = Json.parseToJsonElement(wire).jsonObject
        assertEquals("session.start", root["type"]!!.jsonPrimitive.content)
        val session = root["session"]!!.jsonObject
        assertEquals("gpt-live-1", session["model"]!!.jsonPrimitive.content)
        assertEquals("client", session["delegation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("false", session["store"]!!.jsonPrimitive.content)
        val format = session["audio"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("audio/pcm", format["type"]!!.jsonPrimitive.content)
        assertEquals("24000", format["rate"]!!.jsonPrimitive.content)
        val history = session["input"]!!.jsonArray
        assertEquals(listOf("input_text", "input_text", "output_text"), history.map {
            it.jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content
        })
        assertFalse(wire.contains(config.apiKey))
        assertFalse(config.toString().contains(config.apiKey))
        assertFalse(config.toString().contains(config.instructions))
    }

    @Test
    fun `history keeps recent messages within count and conservative byte budgets`() {
        val history = (1..180).map { LiveHistoryMessage("user", "message $it") }
        val bounded = LiveProtocol.boundedHistory(history)
        assertTrue(bounded.size <= 128)
        assertEquals("message 180", bounded.last().text)
        assertTrue(bounded.sumOf { it.text.toByteArray(Charsets.UTF_8).size + 32 } <= 6_000)

        val long = "start" + "语音🚀".repeat(3_000) + "last"
        val truncated = LiveProtocol.boundedHistory(listOf(LiveHistoryMessage("user", long))).single().text
        assertTrue(long.endsWith(truncated))
        assertTrue(truncated.endsWith("last"))
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size + 32 <= 6_000)
        assertEquals(truncated, truncated.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            LiveProtocol.boundedHistory(listOf(LiveHistoryMessage("tool", "untrusted tool result")))
        }
    }

    @Test
    fun `transcripts preserve text and decimal or exponent timestamps`() {
        val input = event("""{"type":"session.input_transcript.delta","event_id":"fragment-1","delta":" yes, yes ","start_ms":1.25e3,"end_ms":1300.75} """)
            as LiveEvent.Transcript
        assertEquals("user", input.role)
        assertEquals(" yes, yes ", input.delta)
        assertEquals(1250.0, input.startMs, 0.0)
        assertEquals(1300.75, input.endMs, 0.0)
        val output = event("""{"type":"session.output_transcript.delta","delta":" sure","start_ms":1250.5,"end_ms":1.4e3} """)
            as LiveEvent.Transcript
        assertEquals("assistant", output.role)
        assertEquals(" sure", output.delta)
        assertNull(output.eventId)
    }

    @Test
    fun `client delegation uses metadata and ignores other delegation modes or new events`() {
        assertEquals(
            LiveEvent.Delegation("task-1", 1200.5),
            event("""{"type":"session.delegation.created","offset_ms":1.2005e3,"delegation":{"id":"task-1","target":"client","type":"delegation"}}"""),
        )
        assertNull(LiveProtocol.parse("""{"type":"session.delegation.created","delegation":{"target":"responses"}}"""))
        assertNull(LiveProtocol.parse("""{"type":"session.future.event","extra":{"anything":true}}"""))
    }

    @Test
    fun `malformed known audio or numeric events fail without returning partial data`() {
        for (wire in listOf(
            """{"type":"session.output_audio.delta","delta":"%%%"}""",
            """{"type":"session.output_audio.delta","delta":"AQ=="}""",
            """{"type":"session.input_transcript.delta","delta":"hello","start_ms":"NaN","end_ms":5}""",
            """{"type":"session.input_transcript.delta","start_ms":0,"end_ms":5}""",
            """{"type":"session.usage.updated","usage":{"seconds":-1}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { LiveProtocol.parse(wire) }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (event(
            """{"type":"session.output_audio.delta","delta":"AQIDBA=="}""",
        ) as LiveEvent.Audio).bytes)
        assertThrows(IllegalArgumentException::class.java) { LiveProtocol.audio(byteArrayOf(1)) }
    }

    @Test
    fun `Unicode text chunks retain exact content and explicit null delegation`() {
        val text = "a".repeat(449) + "🚀你好e\u0301".repeat(100) + " done "
        val chunks = splitLiveText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 450 })
        assertTrue(chunks.all { it == it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) })
        val update = LiveProtocol.append("session.thinking.append", "context-1", null, "Progress").let {
            Json.parseToJsonElement(it).jsonObject
        }
        assertTrue(update.containsKey("delegation_id"))
        assertEquals("null", update["delegation_id"].toString())
        assertTrue(splitLiveText("").isEmpty())
    }

    private fun event(wire: String): LiveEvent = (LiveProtocol.parse(wire) as LiveServerMessage.Event).value
}
