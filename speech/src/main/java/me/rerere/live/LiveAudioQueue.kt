package me.rerere.live

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Bounds both event count and decoded bytes; a server cannot grow playback memory indefinitely. */
internal class LiveAudioQueue(private val maxBytes: Int = 240_000) {
    private val channel = Channel<ByteArray>(128)
    private val bytes = AtomicInteger(0)

    fun offer(pcm: ByteArray): Boolean {
        if (bytes.addAndGet(pcm.size) > maxBytes) {
            bytes.addAndGet(-pcm.size)
            return false
        }
        if (channel.trySend(pcm).isFailure) {
            bytes.addAndGet(-pcm.size)
            return false
        }
        return true
    }

    suspend fun receive(): ByteArray = channel.receive().also { bytes.addAndGet(-it.size) }

    fun close() = channel.cancel()
}
