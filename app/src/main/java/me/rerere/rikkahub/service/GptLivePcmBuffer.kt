package me.rerere.rikkahub.service

/** A byte-bounded PCM16 queue. A producer never waits for the audio device. */
internal class GptLivePcmBuffer(private val capacity: Int) {
    init {
        require(capacity > 0 && capacity % 2 == 0)
    }

    private val bytes = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var closed = false

    @Synchronized
    fun offer(pcm: ByteArray): Boolean {
        if (closed || pcm.size % 2 != 0 || pcm.size > capacity - size) return false
        val tail = (head + size) % capacity
        val first = minOf(pcm.size, capacity - tail)
        pcm.copyInto(bytes, tail, 0, first)
        pcm.copyInto(bytes, 0, first, pcm.size)
        size += pcm.size
        return true
    }

    @Synchronized
    fun take(maxBytes: Int): ByteArray? {
        require(maxBytes > 0 && maxBytes % 2 == 0)
        if (size == 0) return null
        val count = minOf(size, maxBytes)
        val result = ByteArray(count)
        val first = minOf(count, capacity - head)
        bytes.copyInto(result, 0, head, head + first)
        bytes.copyInto(result, first, 0, count - first)
        head = (head + count) % capacity
        size -= count
        return result
    }

    @Synchronized
    fun clear() {
        bytes.fill(0)
        head = 0
        size = 0
    }

    @Synchronized
    fun close() {
        closed = true
        clear()
    }
}

/** AudioTrack exposes an unsigned, wrapping 32-bit count of frames actually played. */
internal class GptLivePlaybackHead {
    private var previous = 0L
    private var wraps = 0L

    fun update(position: Int): Long {
        val unsigned = position.toLong() and 0xffff_ffffL
        if (unsigned < previous) {
            check(previous - unsigned > Int.MAX_VALUE) { "The audio playback position reset unexpectedly" }
            wraps += 1L shl 32
        }
        previous = unsigned
        return wraps + unsigned
    }
}
