package me.rerere.live

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Native GPT-Live protocol. This intentionally does not use the Realtime ASR protocol. */
class GptLiveConversation(
    context: Context,
    private val httpClient: OkHttpClient,
    private val config: LiveSessionConfig,
) : LiveConversation {
    private val context = context.applicationContext
    private val used = AtomicBoolean(false)

    override suspend fun run(onEvent: (LiveEvent) -> Unit): Unit = withContext(Dispatchers.IO) {
        check(used.compareAndSet(false, true)) { "Create a new Live conversation for each call" }
        require(config.apiKey.isNotBlank()) { "An AI Gateway API key is required" }
        require(config.model.isNotBlank()) { "A GPT-Live model is required" }
        require(config.url.startsWith("wss://")) { "Live conversations require a secure WebSocket URL" }

        coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val sessionClosed = CompletableDeferred<Unit>()
            val transportClosed = CompletableDeferred<Unit>()
            val closing = AtomicBoolean(false)
            val acceptingEvents = AtomicBoolean(true)
            val sendLock = Any()
            val eventLock = Any()
            val output = LiveAudioQueue()
            val audio = LiveAudioDevice(context) { message ->
                finished.completeExceptionally(IOException(message))
            }
            var recorder: Job? = null
            var player: Job? = null
            var speakingMonitor: Job? = null

            fun fail(error: Throwable) {
                finished.completeExceptionally(error)
            }

            fun send(socket: WebSocket, text: String) {
                synchronized(sendLock) {
                    if (closing.get()) return
                    if (socket.queueSize() > MAX_OUTBOUND_BYTES) {
                        throw IOException("The network is too slow for a live conversation")
                    }
                    if (!socket.send(text)) throw IOException("The Live connection is no longer open")
                }
            }

            val request = Request.Builder().url(config.url).apply {
                config.headers.forEach { (name, value) -> header(name, value) }
                header("Authorization", "Bearer ${config.apiKey}")
            }.build()
            val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closing.get()) {
                        webSocket.cancel()
                        return
                    }
                    runCatching { send(webSocket, GptLiveProtocol.start(config)) }
                        .onFailure(::fail)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(eventLock) {
                        if (!acceptingEvents.get()) return
                        runCatching {
                            when (val event = GptLiveProtocol.parse(text)) {
                                ServerEvent.Started -> ready.complete(Unit)
                                ServerEvent.Closed -> {
                                    sessionClosed.complete(Unit)
                                    finished.complete(Unit)
                                    webSocket.close(1000, "Live session closed")
                                }
                                is ServerEvent.Audio -> if (!closing.get() && event.pcm.isNotEmpty()) {
                                    check(ready.isCompleted) { "Received audio before Live session started" }
                                    if (!output.offer(event.pcm)) {
                                        throw IOException("Live audio playback could not keep up")
                                    }
                                }
                                is ServerEvent.Transcript -> {
                                    // Final transcript fragments may arrive during graceful shutdown.
                                    onEvent(LiveEvent.Transcript(event.value))
                                }
                                is ServerEvent.Delegation -> if (!closing.get()) {
                                    send(webSocket, GptLiveProtocol.unavailableDelegation(event.id))
                                }
                                is ServerEvent.Error -> throw IOException(event.message)
                                ServerEvent.Ignored -> Unit
                            }
                        }.onFailure(::fail)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    if (!sessionClosed.isCompleted && !closing.get()) {
                        fail(IOException("The Live connection closed unexpectedly (code $code)"))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    transportClosed.complete(Unit)
                    if (!sessionClosed.isCompleted && !closing.get()) {
                        fail(IOException("The Live connection ended without confirming session close"))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    transportClosed.complete(Unit)
                    val status = response?.let { " (HTTP ${it.code})" }.orEmpty()
                    fail(IOException("Live connection failed$status: ${t.message.orEmpty()}", t))
                }
            })

            try {
                try {
                    withTimeout(20_000) {
                        select<Unit> {
                            ready.onAwait { }
                            finished.onAwait { throw IOException("The Live session ended before it started") }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    throw IOException("Timed out waiting for the Live session to start")
                }
                currentCoroutineContext().ensureActive()
                config.context.takeLast(24).filter { it.isNotBlank() }.forEach {
                    send(socket, GptLiveProtocol.context(it))
                }
                audio.start()
                onEvent(LiveEvent.Ready)
                recorder = launch {
                    audio.capture { frame -> send(socket, GptLiveProtocol.audio(frame)) }
                }
                player = launch {
                    while (true) audio.play(output.receive())
                }
                speakingMonitor = launch {
                    audio.monitorSpeaking { speaking -> onEvent(LiveEvent.Speaking(speaking)) }
                }
                finished.await()
            } finally {
                synchronized(sendLock) { closing.set(true) }
                // Stop capture and sound immediately, including cancellation from leaving the screen.
                audio.stop()
                withContext(NonCancellable) {
                    recorder?.cancelAndJoin()
                    player?.cancelAndJoin()
                    speakingMonitor?.cancelAndJoin()
                    audio.release()
                    output.close()
                    runCatching { onEvent(LiveEvent.Speaking(false)) }
                    try {
                        if (ready.isCompleted && !transportClosed.isCompleted) {
                            if (!sessionClosed.isCompleted) {
                                socket.send(GptLiveProtocol.close())
                            }
                            withTimeoutOrNull(5_000) {
                                select {
                                    sessionClosed.onAwait { }
                                    transportClosed.onAwait { }
                                }
                                if (!transportClosed.isCompleted) {
                                    socket.close(1000, "Live session closed")
                                    transportClosed.await()
                                }
                            }
                        }
                    } finally {
                        synchronized(eventLock) { acceptingEvents.set(false) }
                        socket.cancel()
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_OUTBOUND_BYTES = 128_000L
    }
}
