package me.rerere.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class GptLiveConversationTest {
    @Test(timeout = 15_000)
    fun clientDelegationReturnsTheAssistantResultOverTheSameLiveConnection() = runBlocking {
        Harness().use { harness ->
            val live = harness.conversation(backendEnabled = true)
            val events = Channel<LiveEvent>(Channel.UNLIMITED)
            val run = async { live.run { events.trySend(it) } }
            try {
                assertTrue(harness.messages.receive().contains("session.start"))
                harness.peer.await().send("""{"type":"session.started"}""")
                assertEquals(LiveEvent.Ready, events.receive())
                harness.peer.await().send(
                    """{"type":"session.delegation.created","offset_ms":100,"delegation":{"id":"d1","target":"client"}}"""
                )
                assertEquals(LiveEvent.Delegation("d1", 100.0), events.receive())
                assertTrue(harness.messages.tryReceive().isFailure)
                live.sendDelegationResult("d1", "助手查到的结果😀")
                val reply = Json.parseToJsonElement(harness.messages.receive()).jsonObject
                assertEquals("session.commentary.append", reply.getValue("type").jsonPrimitive.content)
                assertEquals("d1", reply.getValue("delegation_id").jsonPrimitive.content)
                assertEquals("助手查到的结果😀", reply.getValue("content").jsonPrimitive.content)
            } finally {
                run.cancelAndJoin()
            }
            assertTrue(harness.audio.released)
            assertTrue(harness.messages.receive().contains("session.close"))
            live.sendDelegationResult("d1", "late result")
            assertTrue(harness.messages.tryReceive().isFailure)
        }
    }

    @Test(timeout = 15_000)
    fun voiceWithoutABackendRetainsItsExplicitUnavailableReply() = runBlocking {
        Harness().use { harness ->
            val events = mutableListOf<LiveEvent>()
            val run =
                async { harness.conversation(backendEnabled = false).run { synchronized(events) { events.add(it) } } }
            try {
                harness.messages.receive()
                harness.peer.await().send("""{"type":"session.started"}""")
                harness.peer.await().send("""{"type":"session.delegation.created","delegation":{"id":"d1"}}""")
                val result = withTimeout(3_000) { harness.messages.receive() }
                assertTrue(result.contains("no backend tools configured"))
                assertFalse(synchronized(events) { events.any { it is LiveEvent.Delegation } })
            } finally {
                run.cancelAndJoin()
            }
        }
    }

    private class Harness : Closeable {
        val server = MockWebServer()
        private val client = OkHttpClient()
        val peer = CompletableDeferred<WebSocket>()
        val messages = Channel<String>(Channel.UNLIMITED)
        val audio = FakeAudio()

        init {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    peer.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.trySend(text)
                    if (text.contains("session.close")) webSocket.send("""{"type":"session.closed"}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            server.start()
        }

        fun conversation(backendEnabled: Boolean) = GptLiveConversation(
            webSocketFactory = object : WebSocket.Factory {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                    client.newWebSocket(request.newBuilder().url(server.url("/live")).build(), listener)
            },
            config = LiveSessionConfig(
                "wss://ai-gateway.vercel.sh/v1/live/sessions",
                "test-key",
                "openai/gpt-live-1",
                backendEnabled = backendEnabled,
            ),
            audioFactory = { audio },
            shutdownTimeoutMs = 500,
        )

        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    private class FakeAudio : LiveAudioIO {
        @Volatile var released = false
        override val playbackGeneration = 0L
        override fun start() = Unit
        override suspend fun capture(onFrame: (ByteArray) -> Unit): Nothing = awaitCancellation()
        override suspend fun play(pcm: ByteArray, generation: Long) = Unit
        override suspend fun monitorSpeaking(onSpeaking: (Boolean) -> Unit): Nothing = awaitCancellation()
        override fun interruptPlayback() = Unit
        override fun stop() = Unit
        override fun release() {
            released = true
        }
    }
}
