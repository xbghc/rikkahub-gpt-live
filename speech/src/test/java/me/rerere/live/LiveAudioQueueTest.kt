package me.rerere.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioQueueTest {
    @Test
    fun boundsDecodedBytesAndReusesCapacityAfterPlayback() = runBlocking {
        val queue = LiveAudioQueue(maxBytes = 4)
        val first = byteArrayOf(1, 0, 2, 0)
        assertTrue(queue.offer(first))
        assertFalse(queue.offer(byteArrayOf(3, 0)))
        assertArrayEquals(first, queue.receive())
        assertTrue(queue.offer(byteArrayOf(3, 0)))
        queue.close()
    }

    @Test
    fun boundsNumberOfBufferedEventsAsWellAsBytes() {
        val queue = LiveAudioQueue()
        repeat(128) { assertTrue(queue.offer(byteArrayOf(0, 0))) }
        assertFalse(queue.offer(byteArrayOf(0, 0)))
        queue.close()
        assertFalse(queue.offer(byteArrayOf(0, 0)))
    }

    @Test(expected = CancellationException::class)
    fun closingReleasesAWaitingPlaybackConsumer() = runBlocking<Unit> {
        val queue = LiveAudioQueue()
        queue.close()
        queue.receive()
    }
}
