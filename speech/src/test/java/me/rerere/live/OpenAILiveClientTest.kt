package me.rerere.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenAILiveClientTest {
    private val config = LiveSessionConfig(apiKey = "sk-test-private-key", instructions = "Be concise.")

    @Test
    fun `open sends startup but audio waits for server started`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val ready = async { client.connect(config) }
        runCurrent()
        assertEquals("/v1/live/sessions", factory.socket.request().url.encodedPath)
        assertNull(factory.socket.request().url.query)
        factory.open()
        assertEquals("session.start", type(factory.socket.sent.single()))
        assertFalse(ready.isCompleted)
        assertFalse(client.appendAudio(byteArrayOf(1, 2)))
        factory.event(STARTED)
        runCurrent()
        ready.await()
        assertFalse(client.appendAudio(byteArrayOf(1)))
        assertTrue(client.appendAudio(byteArrayOf(1, 2)))
        assertEquals("session.input_audio.append", type(factory.socket.sent.last()))
        client.dispose()
    }

    @Test
    fun `Vercel gateway uses its native endpoint bearer key and model slug`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val gateway = config.copy(
            apiKey = "vercel-api-key",
            endpoint = "wss://ai-gateway.vercel.sh/v1/live/sessions",
            model = "openai/gpt-live-1",
        )
        val ready = async { client.connect(gateway) }
        runCurrent()
        val request = factory.socket.request()
        assertEquals("ai-gateway.vercel.sh", request.url.host)
        assertEquals("/v1/live/sessions", request.url.encodedPath)
        assertEquals("Bearer vercel-api-key", request.header("Authorization"))
        factory.open()
        val start = Json.parseToJsonElement(factory.socket.sent.single()).jsonObject
        assertEquals("session.start", start["type"]!!.jsonPrimitive.content)
        val session = start["session"]!!.jsonObject
        assertEquals("openai/gpt-live-1", session["model"]!!.jsonPrimitive.content)
        assertEquals("client", session["delegation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        factory.event(STARTED)
        ready.await()
        assertTrue(client.appendAudio(byteArrayOf(1, 2)))
        client.dispose()
    }

    @Test
    fun `microphone state waits for matching type and id and command rejection remains nonfatal`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val events = collectEvents(client)
        connect(client, factory)
        val mute = async { client.setInputMuted(true) }
        runCurrent()
        val id = eventId(factory.socket.sent.last())
        factory.event("""{"type":"session.input_audio.unmuted","client_event_id":"$id"}""")
        factory.event("""{"type":"session.input_audio.muted","client_event_id":"unrelated"}""")
        runCurrent()
        assertFalse(mute.isCompleted)
        factory.event("""{"type":"session.input_audio.muted","client_event_id":"$id"}""")
        mute.await()

        val unmute = async { runCatching { client.setInputMuted(false) } }
        runCurrent()
        val unmuteId = eventId(factory.socket.sent.last())
        factory.event("""{"type":"error","error":{"message":"Rejected ${config.apiKey} Bearer proxy-secret","client_event_id":"$unmuteId"}}""")
        val failure = unmute.await().exceptionOrNull()!!
        assertFalse(failure.message!!.contains(config.apiKey))
        assertFalse(failure.message!!.contains("proxy-secret"))
        assertFalse(events.filterIsInstance<LiveEvent.Error>().single().fatal)
        assertTrue(client.appendAudio(byteArrayOf(1, 2)))
        client.dispose()
    }

    @Test
    fun `microphone acknowledgment timeout stops a session with indeterminate input state`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, commandTimeoutMs = 50)
        val events = collectEvents(client)
        connect(client, factory)
        val mute = async { runCatching { client.setInputMuted(true) } }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertTrue(mute.await().isFailure)
        assertFalse(client.appendAudio(byteArrayOf(1, 2)))
        assertTrue(factory.socket.canceled)
        assertEquals("command_timeout", client.close().reason)
        assertTrue(events.filterIsInstance<LiveEvent.Error>().single().fatal)
    }

    @Test
    fun `server close arriving inside close send is retained despite late failures and dispose`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val events = collectEvents(client)
        connect(client, factory)
        factory.socket.onSend = { wire ->
            if (type(wire) == "session.close") {
                factory.event("""{"type":"session.closed","reason":"close_requested","usage":{"seconds":15.25}}""")
            }
        }
        val result = client.close()
        factory.fail()
        factory.transportClosed()
        client.dispose()
        assertEquals(LiveCloseResult(true, "close_requested", 15.25), result)
        assertEquals(result, client.close())
        assertEquals(1, events.filterIsInstance<LiveEvent.Closed>().size)
        assertTrue(events.filterIsInstance<LiveEvent.Error>().isEmpty())
        assertEquals(1, factory.socket.sent.count { type(it) == "session.close" })
    }

    @Test
    fun `transport loss keeps latest cumulative usage and never fabricates session closed`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val events = collectEvents(client)
        connect(client, factory)
        factory.event("""{"type":"session.usage.updated","usage":{"seconds":12}}""")
        factory.event("""{"type":"session.usage.updated","usage":{"seconds":15}}""")
        factory.transportClosed()
        factory.fail()
        val result = client.close()
        assertEquals(LiveCloseResult(false, "connection_lost", 15.0), result)
        assertTrue(events.filterIsInstance<LiveEvent.Closed>().isEmpty())
        assertEquals(1, events.filterIsInstance<LiveEvent.Error>().size)
        assertTrue(factory.socket.canceled)
    }

    @Test
    fun `parallel close callers share bounded finalization and block subsequent audio`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, closeTimeoutMs = 50)
        val events = collectEvents(client)
        connect(client, factory)
        val first = async { client.close() }
        val second = async { client.close() }
        runCurrent()
        assertFalse(client.appendAudio(byteArrayOf(1, 2)))
        assertFalse(first.isCompleted)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(LiveCloseResult(false, "close_timeout", null), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, factory.socket.sent.count { type(it) == "session.close" })
        assertEquals(1, events.filterIsInstance<LiveEvent.Error>().size)
        assertTrue(factory.socket.canceled)
    }

    @Test
    fun `network byte budget includes next frame and fails instead of silently dropping audio`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val events = collectEvents(client)
        connect(client, factory)
        val audio = byteArrayOf(1, 2)
        factory.socket.queuedBytes = 128_000 - LiveProtocol.audio(audio).toByteArray().size + 1L
        val sentBefore = factory.socket.sent.size
        assertFalse(client.appendAudio(audio))
        assertEquals(sentBefore, factory.socket.sent.size)
        assertTrue(factory.socket.canceled)
        assertEquals("outgoing_buffer_full", client.close().reason)
        assertTrue(events.filterIsInstance<LiveEvent.Error>().single().fatal)
    }

    @Test
    fun `bounded event buffer retains terminal failure without an active collector`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, eventCapacity = 1)
        connect(client, factory)
        // session.started occupies the only slot. Losing transcript context terminates this session.
        factory.event("""{"type":"session.input_transcript.delta","delta":"hello","start_ms":0,"end_ms":10}""")
        val events = client.events.toList()
        assertTrue(events.single() is LiveEvent.Error)
        assertTrue((events.single() as LiveEvent.Error).fatal)
        assertEquals("event_buffer_full", client.close().reason)
        assertTrue(factory.socket.canceled)
    }

    @Test
    fun `normal server finalization survives a full queue and immediate dispose`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, eventCapacity = 1)
        connect(client, factory)
        factory.event("""{"type":"session.closed","reason":"expired","usage":{"seconds":600.5}}""")
        client.dispose()
        assertEquals(LiveCloseResult(true, "expired", 600.5), client.close())
        assertEquals(listOf(LiveEvent.Closed("expired", 600.5)), client.events.toList())
    }

    @Test
    fun `synchronous handshake failure before factory returns cannot strand startup waiter`() = runTest {
        val factory = FakeFactory().apply { failSynchronously = true }
        val client = OpenAILiveClient(factory)
        val result = runCatching { client.connect(config) }
        assertTrue(result.isFailure)
        assertTrue(factory.socket.canceled)
        assertFalse(result.exceptionOrNull()!!.message!!.contains(config.apiKey))
        assertTrue(client.events.toList().single() is LiveEvent.Error)
    }

    @Test
    fun `startup timeout cancels transport and ignores late open and started callbacks`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, startupTimeoutMs = 50)
        val connection = async { runCatching { client.connect(config) } }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertTrue(connection.await().isFailure)
        factory.open()
        factory.event(STARTED)
        assertTrue(factory.socket.sent.isEmpty())
        assertEquals("startup_timeout", client.close().reason)
        assertTrue(client.events.toList().single() is LiveEvent.Error)
    }

    @Test
    fun `caller cancellation remains cancellation rather than an internal timeout error`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory, startupTimeoutMs = 1_000)
        val connection = async { runCatching { withTimeout(50) { client.connect(config) } } }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertTrue(connection.await().exceptionOrNull() is CancellationException)
        assertEquals("startup_canceled", client.close().reason)
        assertTrue(factory.socket.canceled)
    }

    @Test
    fun `commentary uses safe chunks and server rejection is correlated without dumping secrets`() = runTest {
        val factory = FakeFactory()
        val client = OpenAILiveClient(factory)
        val events = collectEvents(client)
        connect(client, factory)
        val text = "你好🚀".repeat(120)
        assertTrue(client.appendCommentary("task-1", text))
        val updates = factory.socket.sent.drop(1).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(text, updates.joinToString("") { it["content"]!!.jsonPrimitive.content })
        assertTrue(updates.all { it["content"]!!.jsonPrimitive.content.toByteArray().size <= 450 })
        assertTrue(updates.all { it["delegation_id"]!!.jsonPrimitive.content == "task-1" })
        val id = updates.first()["event_id"]!!.jsonPrimitive.content
        factory.event("""{"type":"error","error":{"client_event_id":"$id","message":"Invalid ${config.apiKey}"}}""")
        val error = events.filterIsInstance<LiveEvent.Error>().single()
        assertEquals(id, error.clientEventId)
        assertFalse(error.message.contains(config.apiKey))
        assertFalse(error.fatal)
        client.dispose()
    }

    private suspend fun TestScope.connect(client: OpenAILiveClient, factory: FakeFactory) {
        val ready = async { client.connect(config) }
        runCurrent()
        factory.open()
        factory.event(STARTED)
        ready.await()
    }

    private fun TestScope.collectEvents(client: OpenAILiveClient): MutableList<LiveEvent> {
        val result = mutableListOf<LiveEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.toList(result) }
        return result
    }

    private fun type(wire: String): String = Json.parseToJsonElement(wire).jsonObject["type"]!!.jsonPrimitive.content

    private fun eventId(wire: String): String = Json.parseToJsonElement(wire).jsonObject["event_id"]!!.jsonPrimitive.content

    private class FakeFactory : WebSocket.Factory {
        lateinit var socket: FakeSocket
        lateinit var listener: WebSocketListener
        var failSynchronously = false

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            socket = FakeSocket(request)
            if (failSynchronously) listener.onFailure(socket, IOException("sk-test-private-key"), null)
            return socket
        }

        fun open() = listener.onOpen(socket, Response.Builder()
            .request(socket.request())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build())

        fun event(wire: String) = listener.onMessage(socket, wire)

        fun fail() = listener.onFailure(socket, IOException("sk-test-private-key"), null)

        fun transportClosed() = listener.onClosed(socket, 1006, "connection lost")
    }

    private class FakeSocket(private val request: Request) : WebSocket {
        val sent = mutableListOf<String>()
        var queuedBytes = 0L
        var canceled = false
        var onSend: ((String) -> Unit)? = null

        override fun request(): Request = request
        override fun queueSize(): Long = queuedBytes
        override fun send(text: String): Boolean {
            if (canceled) return false
            sent += text
            onSend?.invoke(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean = error("Live uses JSON text events")
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { canceled = true }
    }

    private companion object {
        const val STARTED = """{"type":"session.started","session":{"id":"live-session-1"}}"""
    }
}
