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
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveConversationTest {
    @Test(timeout = 15_000)
    fun waitsForSetupBeforeRecordingAndSendsContextThenPcm16k() = runBlocking {
        Harness().use { harness ->
            val conversation = harness.conversation(config.copy(context = listOf("User: Hi", "Assistant: Hello")))
            val run = async { runCatching { conversation.run { } } }
            try {
                val setup = Json.parseToJsonElement(harness.messages.receive()).jsonObject.getValue("setup").jsonObject
                assertEquals(config.model, setup.getValue("model").jsonPrimitive.content)
                assertTrue(setup.containsKey("inputAudioTranscription"))
                assertTrue(setup.containsKey("outputAudioTranscription"))
                assertFalse(harness.audio.started.get())
                assertTrue(harness.messages.tryReceive().isFailure)
                assertEquals(
                    "Bearer $TOKEN",
                    harness.server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Authorization")
                )

                harness.peer.await().send("{\"setupComplete\":{}}")
                val context = Json.parseToJsonElement(harness.messages.receive()).jsonObject
                assertTrue(context.containsKey("clientContent"))
                val audio = Json.parseToJsonElement(harness.messages.receive()).jsonObject
                    .getValue("realtimeInput").jsonObject.getValue("audio").jsonObject
                assertEquals("audio/pcm;rate=16000", audio.getValue("mimeType").jsonPrimitive.content)
                assertEquals(
                    640,
                    java.util.Base64.getDecoder().decode(audio.getValue("data").jsonPrimitive.content).size
                )
                assertTrue(harness.audio.started.get())
            } finally {
                run.cancelAndJoin()
            }
            assertTrue(harness.audio.stopped.get())
            assertTrue(harness.audio.released.get())
            assertTrue(harness.messages.receive().contains("audioStreamEnd"))
        }
    }

    @Test(timeout = 15_000)
    fun receivesBinaryEventsWithAllAudioPartsAndBothTranscripts() = runBlocking {
        Harness().use { harness ->
            val events = Channel<LiveEvent>(Channel.UNLIMITED)
            val run = async { runCatching { harness.conversation().run { events.trySend(it) } } }
            try {
                harness.messages.receive()
                harness.peer.await().send("{\"setupComplete\":{}}".encodeUtf8())
                harness.audio.didStart.await()
                harness.peer.await().send(
                    """{"serverContent":{
                        "modelTurn":{"parts":[
                            {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQACAA=="}},
                            {"text":"not duplicated"},
                            {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwAEAA=="}}
                        ]},
                        "inputTranscription":{"text":"你好","startOffset":"0.5s","endOffset":"0.8s"},
                        "outputTranscription":{"text":"你好！"},"turnComplete":true
                    }}""".trimIndent().encodeUtf8()
                )
                assertArrayEquals(byteArrayOf(1, 0, 2, 0), harness.audio.played.receive())
                assertArrayEquals(byteArrayOf(3, 0, 4, 0), harness.audio.played.receive())
                val transcripts = mutableListOf<LiveTranscript>()
                withTimeout(3_000) {
                    while (transcripts.size < 2) {
                        (events.receive() as? LiveEvent.Transcript)?.let { transcripts.add(it.transcript) }
                    }
                }
                assertEquals(LiveTranscript(LiveSpeaker.User, "你好", 500, 800), transcripts[0])
                assertEquals("你好！", transcripts[1].text)
                assertEquals(LiveSpeaker.Assistant, transcripts[1].speaker)
            } finally {
                run.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 15_000)
    fun interruptClearsQueuedAndAlreadyDequeuedAudioBeforeNewSpeech() = runBlocking {
        Harness().use { harness ->
            harness.audio.holdFirstFrame = true
            val run = async { runCatching { harness.conversation().run { } } }
            try {
                harness.messages.receive()
                val peer = harness.peer.await()
                peer.send("{\"setupComplete\":{}}")
                harness.audio.didStart.await()
                peer.send(audioMessage("AQACAA==", "AwAEAA=="))
                harness.audio.firstFrameDequeued.await()
                peer.send("{\"serverContent\":{\"interrupted\":true}}")
                harness.audio.didInterrupt.await()
                harness.audio.allowFirstFrame.complete(Unit)
                peer.send(audioMessage("BQAGAA=="))
                assertArrayEquals(byteArrayOf(5, 0, 6, 0), harness.audio.played.receive())
                assertTrue(harness.audio.played.tryReceive().isFailure)
                assertEquals(1L, harness.audio.playbackGeneration)
            } finally {
                run.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 15_000)
    fun authenticationFailuresKeepStatusAndReasonWithoutReflectingToken() = runBlocking {
        for (status in listOf(401, 403)) {
            Harness(upgrade = false).use { harness ->
                harness.server.enqueue(
                    MockResponse().setResponseCode(status).setHeader("x-request-id", "cloud-request-123")
                        .setBody("""{"error":{"code":$status,"message":"Permission denied: $TOKEN"}}""")
                )
                val failure = runCatching { harness.conversation().run { } }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(failure?.message.orEmpty().contains("HTTP $status"))
                assertTrue(failure?.message.orEmpty().contains("Permission denied"))
                assertTrue(failure?.message.orEmpty().contains("cloud-request-123"))
                assertFalse(failure?.message.orEmpty().contains(TOKEN))
                // Coroutine stack-trace recovery may attach a copy of the already sanitized exception.
                generateSequence(failure) { it.cause }.forEach {
                    assertFalse(it.message.orEmpty().contains(TOKEN))
                    assertFalse(it.message.orEmpty().contains("private-audio-data"))
                }
                assertFalse(harness.audio.started.get())
                assertTrue(harness.audio.released.get())
            }
        }
    }

    @Test(timeout = 15_000)
    fun closeReasonIsSanitizedAndReleasesAudio() = runBlocking {
        Harness().use { harness ->
            val run = async { runCatching { harness.conversation().run { } } }
            harness.messages.receive()
            val peer = harness.peer.await()
            peer.send("{\"setupComplete\":{}}")
            harness.audio.didStart.await()
            peer.close(1008, "Access denied $TOKEN")
            val failure = run.await().exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("1008"))
            assertTrue(failure?.message.orEmpty().contains("Access denied"))
            assertFalse(failure?.message.orEmpty().contains(TOKEN))
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun serverErrorsAfterUpgradePreserveGoogleStatusWithoutCredentials() = runBlocking {
        Harness().use { harness ->
            val run = async { runCatching { harness.conversation().run { } } }
            harness.messages.receive()
            harness.peer.await().send(
                """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"Access denied $TOKEN"}}"""
            )
            val failure = run.await().exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("PERMISSION_DENIED"))
            assertTrue(failure?.message.orEmpty().contains("403"))
            assertFalse(failure?.message.orEmpty().contains(TOKEN))
            assertFalse(harness.audio.started.get())
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun cancelledSetupDoesNotStartMicrophoneAndReleasesDevices() = runBlocking {
        Harness().use { harness ->
            val run = async { runCatching { harness.conversation().run { } } }
            harness.messages.receive()
            run.cancelAndJoin()
            assertFalse(harness.audio.started.get())
            assertTrue(harness.audio.stopped.get())
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun setupTimeoutIsBoundedAndReleasesDevices() = runBlocking {
        Harness().use { harness ->
            val failure = runCatching { harness.conversation(setupTimeoutMs = 100).run { } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Timed out"))
            assertFalse(harness.audio.started.get())
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun goAwayDeliversRemainingTranscriptAndEndsWithExplicitReconnectMessage() = runBlocking {
        Harness().use { harness ->
            val transcripts = Channel<LiveTranscript>(Channel.UNLIMITED)
            val run = async {
                runCatching {
                    harness.conversation().run { if (it is LiveEvent.Transcript) transcripts.trySend(it.transcript) }
                }
            }
            harness.messages.receive()
            val peer = harness.peer.await()
            peer.send("{\"setupComplete\":{}}")
            harness.audio.didStart.await()
            peer.send("{\"goAway\":{\"timeLeft\":\"0.2s\"}}")
            peer.send("""{"serverContent":{"inputTranscription":{"text":"最后一句"}}}""")
            assertEquals("最后一句", transcripts.receive().text)
            assertTrue(run.await().exceptionOrNull()?.message.orEmpty().contains("重新开始"))
            assertEquals(1, harness.server.requestCount)
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun malformedServerPayloadNeverBecomesErrorText() = runBlocking {
        Harness().use { harness ->
            val run = async { runCatching { harness.conversation().run { } } }
            harness.messages.receive()
            harness.peer.await().send("{invalid-json: $TOKEN private-audio-data}")
            val failure = run.await().exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure?.message.orEmpty().contains(TOKEN))
            assertFalse(failure?.message.orEmpty().contains("private-audio-data"))
            // Coroutine stack-trace recovery may attach a copy of the already sanitized exception.
            generateSequence(failure) { it.cause }.forEach {
                assertFalse(it.message.orEmpty().contains(TOKEN))
                assertFalse(it.message.orEmpty().contains("private-audio-data"))
            }
            assertTrue(harness.audio.released.get())
        }
    }

    @Test(timeout = 15_000)
    fun microphoneStartFailureClosesSocketAndReleasesDevices() = runBlocking {
        Harness().use { harness ->
            harness.audio.failStart = true
            val run = async { runCatching { harness.conversation().run { } } }
            harness.messages.receive()
            harness.peer.await().send("{\"setupComplete\":{}}")
            assertTrue(run.await().exceptionOrNull() is IOException)
            assertTrue(harness.audio.released.get())
        }
    }

    private class Harness(upgrade: Boolean = true) : Closeable {
        val server = MockWebServer()
        private val client = OkHttpClient()
        val peer = CompletableDeferred<WebSocket>()
        val messages = Channel<String>(Channel.UNLIMITED)
        val audio = FakeAudio()

        init {
            if (upgrade) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        peer.complete(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        messages.trySend(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, "Test session ended")
                    }
                }))
            }
            server.start()
        }

        fun conversation(sessionConfig: LiveSessionConfig = config, setupTimeoutMs: Long = 3_000) =
            GeminiLiveConversation(
                webSocketFactory = object : WebSocket.Factory {
                    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                        // Keep production WSS validation; replace only the test transport destination.
                        client.newWebSocket(request.newBuilder().url(server.url("/live")).build(), listener)
                },
                config = sessionConfig,
                audioFactory = { audio },
                setupTimeoutMs = setupTimeoutMs,
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
        val started = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val didStart = CompletableDeferred<Unit>()
        val didInterrupt = CompletableDeferred<Unit>()
        val firstFrameDequeued = CompletableDeferred<Unit>()
        val allowFirstFrame = CompletableDeferred<Unit>()
        val played = Channel<ByteArray>(Channel.UNLIMITED)
        var holdFirstFrame = false
        var failStart = false
        private val generation = AtomicLong(0)
        override val playbackGeneration: Long get() = generation.get()

        override fun start() {
            if (failStart) throw IOException("Microphone is unavailable")
            started.set(true)
            didStart.complete(Unit)
        }

        override suspend fun capture(onFrame: (ByteArray) -> Unit) {
            onFrame(ByteArray(640))
            awaitCancellation()
        }

        override suspend fun play(pcm: ByteArray, generation: Long) {
            if (holdFirstFrame && firstFrameDequeued.complete(Unit)) allowFirstFrame.await()
            if (generation == playbackGeneration && !stopped.get()) played.send(pcm)
        }

        override suspend fun monitorSpeaking(onSpeaking: (Boolean) -> Unit) = awaitCancellation()

        override fun interruptPlayback() {
            generation.incrementAndGet()
            didInterrupt.complete(Unit)
        }

        override fun stop() {
            stopped.set(true)
        }

        override fun release() {
            released.set(true)
        }
    }

    private companion object {
        const val TOKEN = "test-cloud-oauth-credential"
        val config = LiveSessionConfig(
            url = "wss://us-central1-aiplatform.googleapis.com/ws/" +
                "google.cloud.aiplatform.v1.LlmBidiService/BidiGenerateContent",
            apiKey = TOKEN,
            model = "projects/test-project/locations/us-central1/publishers/google/models/gemini-3.8-live",
            instructions = "Be concise",
        )

        fun audioMessage(vararg parts: String): String {
            val jsonParts = parts.joinToString(",") {
                """{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$it"}}"""
            }
            return """{"serverContent":{"modelTurn":{"parts":[$jsonParts]}}}"""
        }
    }
}
