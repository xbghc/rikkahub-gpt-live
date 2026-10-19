package me.rerere.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One primary GPT Live session. Create a new client for a new session; no implicit reconnect/replay.
 * Collect [events] once, preferably before [connect], and process audio without blocking the collector.
 * A slow consumer or full network queue ends the session instead of silently dropping live context.
 */
class OpenAILiveClient internal constructor(
    private val socketFactory: WebSocket.Factory,
    private val startupTimeoutMs: Long = 20_000,
    private val commandTimeoutMs: Long = 5_000,
    private val closeTimeoutMs: Long = 15_000,
    eventCapacity: Int = 64,
) {
    constructor(httpClient: OkHttpClient) : this(socketFactory = privateTransport(httpClient))

    private enum class State { New, Connecting, Active, Closing, Terminal }

    private data class PendingCommand(
        val id: String,
        val acknowledgment: String,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private val muteMutex = Mutex()
    private val eventQueue = Channel<LiveEvent>(eventCapacity.also { require(it > 0) })
    val events: Flow<LiveEvent> = eventQueue.receiveAsFlow()

    private var state = State.New
    private var webSocket: WebSocket? = null
    private var secret = ""
    private var latestUsage: Double? = null
    private val started = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<LiveCloseResult>()
    private val pendingCommands = mutableMapOf<String, PendingCommand>()

    suspend fun connect(config: LiveSessionConfig) {
        val key = config.apiKey.trim()
        require(key.isNotEmpty() && key.all { it.code in 33..126 }) { "A valid Live API key is required" }
        val request = request(config.endpoint, key)
        val startEvent = LiveProtocol.start(config, eventId())
        synchronized(lock) {
            check(state == State.New) { "Create a new Live client for each session" }
            state = State.Connecting
            secret = key
            try {
                val socket = socketFactory.newWebSocket(request, listener(startEvent))
                // A synchronous callback may have already attached (or terminated) the socket.
                if (state == State.Terminal) {
                    socket.cancel()
                } else if (webSocket == null) {
                    webSocket = socket
                } else if (webSocket !== socket) {
                    socket.cancel()
                }
            } catch (_: Exception) {
                failLocked("Could not open the GPT Live connection", "startup_failed")
            }
        }
        try {
            if (withTimeoutOrNull(startupTimeoutMs) {
                    started.await()
                    true
                } == null) {
                synchronized(lock) { failLocked("GPT Live did not start in time", "startup_timeout") }
                throw LiveClientException("GPT Live did not start in time")
            }
        } catch (error: CancellationException) {
            synchronized(lock) { failLocked("GPT Live startup was canceled", "startup_canceled") }
            throw error
        }
    }

    /** Accepts raw mono PCM16 LE at 24 kHz. False means no further audio should be captured for this send. */
    fun appendAudio(pcm: ByteArray): Boolean {
        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > MAX_AUDIO_CHUNK_BYTES) return false
        val text = LiveProtocol.audio(pcm)
        return synchronized(lock) {
            state == State.Active && sendLocked(listOf(text))
        }
    }

    /** Waits for the matching server ACK; an indeterminate microphone state terminates the session. */
    suspend fun setInputMuted(muted: Boolean) = muteMutex.withLock {
        val command = PendingCommand(
            id = eventId(),
            acknowledgment = if (muted) "session.input_audio.muted" else "session.input_audio.unmuted",
        )
        synchronized(lock) {
            if (state != State.Active) throw LiveClientException("GPT Live is not connected")
            pendingCommands[command.id] = command
            val type = if (muted) "session.input_audio.mute" else "session.input_audio.unmute"
            sendLocked(listOf(LiveProtocol.command(type, command.id)))
        }
        try {
            if (withTimeoutOrNull(commandTimeoutMs) {
                    command.result.await()
                    true
                } == null) {
                synchronized(lock) { failLocked("GPT Live did not confirm the microphone change", "command_timeout") }
                throw LiveClientException("GPT Live did not confirm the microphone change")
            }
        } catch (error: CancellationException) {
            synchronized(lock) {
                if (state == State.Active) failLocked("The Live microphone change was canceled", "command_canceled")
            }
            throw error
        } finally {
            synchronized(lock) { pendingCommands.remove(command.id) }
        }
    }

    /** True means all chunks were queued, not that they were acknowledged or spoken. */
    fun appendCommentary(delegationId: String?, content: String): Boolean =
        appendContext("session.commentary.append", delegationId, content)

    fun appendThinking(delegationId: String?, content: String): Boolean =
        appendContext("session.thinking.append", delegationId, content)

    fun appendInstructions(delegationId: String?, content: String): Boolean =
        appendContext("session.instructions.append", delegationId, content)

    private fun appendContext(type: String, delegationId: String?, content: String): Boolean {
        if (content.isBlank() || content.length > MAX_APPEND_BYTES ||
            content.toByteArray(Charsets.UTF_8).size > MAX_APPEND_BYTES || delegationId?.isBlank() == true
        ) {
            return false
        }
        val chunks = splitLiveText(content).map {
            LiveProtocol.append(type, eventId(), delegationId, it)
        }
        return synchronized(lock) {
            state == State.Active && sendLocked(chunks)
        }
    }

    /** Stops new input and waits for session.closed. Repeated close calls share the same final result. */
    suspend fun close(): LiveCloseResult {
        synchronized(lock) {
            when (state) {
                State.New, State.Connecting -> endLocked(
                    LiveCloseResult(false, "not_started", latestUsage),
                    event = null,
                    failureMessage = "GPT Live was closed before startup completed",
                )
                State.Active -> {
                    state = State.Closing
                    rejectPendingLocked("GPT Live is closing")
                    sendLocked(listOf(LiveProtocol.command("session.close", eventId())))
                }
                State.Closing, State.Terminal -> Unit
            }
        }
        return try {
            withTimeoutOrNull(closeTimeoutMs) { closed.await() } ?: run {
                synchronized(lock) {
                    failLocked("GPT Live closed without confirming final usage", "close_timeout")
                }
                closed.await()
            }
        } catch (error: CancellationException) {
            dispose()
            throw error
        }
    }

    /** Releases this session only; the caller's shared OkHttp dispatcher and connection pool are retained. */
    fun dispose() {
        synchronized(lock) {
            if (state != State.Terminal) {
                endLocked(
                    LiveCloseResult(false, "disposed", latestUsage),
                    event = null,
                    failureMessage = "GPT Live was disposed before finalization",
                )
            }
        }
    }

    private fun listener(startEvent: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (state != State.Connecting || (this@OpenAILiveClient.webSocket != null &&
                        this@OpenAILiveClient.webSocket !== webSocket)
                ) {
                    webSocket.cancel()
                    return
                }
                this@OpenAILiveClient.webSocket = webSocket
                sendLocked(listOf(startEvent))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                if (!isCurrentLocked(webSocket)) return
                val message = try {
                    LiveProtocol.parse(text)
                } catch (_: Exception) {
                    failLocked("Received an invalid GPT Live event", "protocol_error")
                    return
                }
                when (message) {
                    is LiveServerMessage.Acknowledgment -> {
                        val command = pendingCommands[message.clientEventId]
                        if (command?.acknowledgment == message.type) command.result.complete(Unit)
                    }
                    is LiveServerMessage.Event -> handleEventLocked(message.value)
                    null -> Unit
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(lock) {
                if (isCurrentLocked(webSocket)) {
                    failLocked("Received an unsupported binary GPT Live event", "protocol_error")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (isCurrentLocked(webSocket)) {
                    failLocked("GPT Live disconnected before confirming final usage", "connection_lost")
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (isCurrentLocked(webSocket)) {
                    failLocked("GPT Live disconnected before confirming final usage", "connection_lost")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (state == State.Connecting && this@OpenAILiveClient.webSocket == null) {
                    this@OpenAILiveClient.webSocket = webSocket
                }
                runCatching { response?.body?.close() }
                if (!isCurrentLocked(webSocket)) return
                val status = response?.code?.let { " (HTTP $it)" }.orEmpty()
                // Throwable messages can contain URLs, headers or credentials from an upstream proxy.
                failLocked("GPT Live connection failed$status", "connection_lost")
            }
        }
    }

    private fun handleEventLocked(event: LiveEvent) {
        when (event) {
            is LiveEvent.Started -> {
                if (state != State.Connecting) return
                state = State.Active
                if (publishLocked(event)) started.complete(Unit)
            }
            is LiveEvent.Closed -> {
                val safeEvent = event.copy(reason = redact(event.reason))
                endLocked(
                    LiveCloseResult(true, safeEvent.reason, safeEvent.seconds),
                    safeEvent,
                    "GPT Live ended before the command completed",
                    graceful = true,
                )
            }
            is LiveEvent.Error -> {
                val safeEvent = event.copy(message = redact(event.message))
                if (state == State.Connecting) {
                    failLocked(safeEvent.message, "startup_error", safeEvent.clientEventId)
                } else {
                    pendingCommands[safeEvent.clientEventId]?.result?.completeExceptionally(
                        LiveClientException(safeEvent.message),
                    )
                    publishLocked(safeEvent)
                }
            }
            is LiveEvent.Usage -> {
                latestUsage = event.seconds
                if (state != State.Connecting) publishLocked(event)
            }
            is LiveEvent.Audio -> {
                if (state == State.Active && event.bytes.isNotEmpty()) publishLocked(event)
            }
            is LiveEvent.Delegation -> if (state == State.Active) publishLocked(event)
            is LiveEvent.Transcript -> if (state == State.Active || state == State.Closing) publishLocked(event)
        }
    }

    private fun isCurrentLocked(socket: WebSocket): Boolean = webSocket === socket && state != State.Terminal

    private fun sendLocked(messages: List<String>): Boolean {
        val socket = webSocket ?: return false
        val bytes = messages.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        if (socket.queueSize() + bytes > MAX_OUTGOING_QUEUE_BYTES) {
            failLocked("The GPT Live network connection cannot keep up with audio", "outgoing_buffer_full")
            return false
        }
        for (message in messages) {
            val accepted = try {
                socket.send(message)
            } catch (_: Exception) {
                false
            }
            if (!accepted) {
                failLocked("The GPT Live connection stopped accepting data", "send_failed")
                return false
            }
            if (state == State.Terminal) return false
        }
        return true
    }

    private fun publishLocked(event: LiveEvent): Boolean {
        if (eventQueue.trySend(event).isSuccess) return true
        failLocked("GPT Live events could not be processed in time", "event_buffer_full")
        return false
    }

    private fun failLocked(message: String, reason: String, clientEventId: String? = null) {
        val safeMessage = redact(message)
        endLocked(
            LiveCloseResult(false, reason, latestUsage),
            LiveEvent.Error(safeMessage, fatal = true, clientEventId = clientEventId),
            safeMessage,
        )
    }

    private fun endLocked(
        result: LiveCloseResult,
        event: LiveEvent?,
        failureMessage: String,
        graceful: Boolean = false,
    ) {
        if (state == State.Terminal) return
        state = State.Terminal
        val socket = webSocket
        webSocket = null
        started.completeExceptionally(LiveClientException(failureMessage))
        rejectPendingLocked(failureMessage)

        if (event != null) {
            // Reserve delivery of the single terminal event even when the bounded buffer is full.
            // Overflow already makes the session fail: do not keep streaming with lost context.
            while (true) {
                val sent = eventQueue.trySend(event)
                if (sent.isSuccess || sent.isClosed) break
                eventQueue.tryReceive()
            }
        } else {
            // Explicit disposal cancels buffered work; an already finalized session never reaches here.
            while (eventQueue.tryReceive().isSuccess) {
                // Drain canceled work before completing the stream.
            }
        }
        eventQueue.close()
        closed.complete(result)
        secret = ""
        if (socket != null) {
            val closing = graceful && runCatching { socket.close(1000, "session finalized") }.getOrDefault(false)
            if (!closing) runCatching { socket.cancel() }
        }
    }

    private fun rejectPendingLocked(message: String) {
        pendingCommands.values.forEach { it.result.completeExceptionally(LiveClientException(message)) }
        pendingCommands.clear()
    }

    private fun redact(message: String): String {
        var safe = if (secret.isEmpty()) message else message.replace(secret, "<redacted>")
        safe = safe.replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer <redacted>")
        safe = safe.replace(Regex("sk-[A-Za-z0-9_-]+"), "<redacted>")
        return safe.take(640)
    }

    private fun eventId(): String = "live_${UUID.randomUUID()}"

    companion object {
        private const val MAX_AUDIO_CHUNK_BYTES = 24_000
        private const val MAX_APPEND_BYTES = 32_000
        private const val MAX_OUTGOING_QUEUE_BYTES = 128_000L

        private fun privateTransport(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
            // The application client logs request headers. Retain proxy/DNS/TLS settings, but keep
            // this credential-bearing connection outside request logging and request rewriting.
            interceptors().clear()
            networkInterceptors().clear()
            eventListener(EventListener.NONE)
            followRedirects(false)
            followSslRedirects(false)
            readTimeout(0, TimeUnit.MILLISECONDS)
            callTimeout(0, TimeUnit.MILLISECONDS)
            pingInterval(20, TimeUnit.SECONDS)
        }.build()

        private fun request(endpoint: String, key: String): Request {
            try {
                val uri = URI(endpoint.trim())
                val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
                require(uri.scheme == "wss" || (uri.scheme == "ws" && loopback))
                require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null)
                require(uri.rawQuery == null)
                return Request.Builder()
                    .url(endpoint.trim())
                    .header("Authorization", "Bearer $key")
                    .build()
            } catch (_: Exception) {
                throw LiveClientException("Use a wss:// Live endpoint without credentials or query parameters")
            }
        }
    }
}
