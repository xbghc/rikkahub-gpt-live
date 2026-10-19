package me.rerere.rikkahub.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GptLivePcmBufferTest {
    @Test
    fun overflowRejectsWholeChunkWithoutDroppingExistingAudio() {
        val queue = GptLivePcmBuffer(8)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(queue.offer(byteArrayOf(7, 8, 9, 10)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), queue.take(8))
        assertNull(queue.take(2))
    }

    @Test
    fun partialReadsAndWrappedWritesPreserveAudioOrder() {
        val queue = GptLivePcmBuffer(8)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), queue.take(4))
        assertTrue(queue.offer(byteArrayOf(7, 8, 9, 10, 11, 12)))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), queue.take(4))
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), queue.take(4))
        assertNull(queue.take(2))
    }

    @Test
    fun producerCanReuseItsBufferAfterEnqueue() {
        val queue = GptLivePcmBuffer(8)
        val frame = byteArrayOf(1, 2, 3, 4)
        assertTrue(queue.offer(frame))
        frame.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), queue.take(4))
    }

    @Test
    fun invalidPcmDoesNotShiftSampleAlignment() {
        val queue = GptLivePcmBuffer(8)
        assertFalse(queue.offer(byteArrayOf(1, 2, 3)))
        assertTrue(queue.offer(byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(4, 5), queue.take(8))
    }

    @Test
    fun clearingDiscardsAllQueuedSpeechAndRestoresCapacity() {
        val queue = GptLivePcmBuffer(4)
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4)))
        queue.clear()
        assertNull(queue.take(4))
        assertTrue(queue.offer(byteArrayOf(5, 6, 7, 8)))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), queue.take(4))
    }

    @Test
    fun lateServerFramesCannotRepopulateAClosedSession() {
        val queue = GptLivePcmBuffer(4)
        assertTrue(queue.offer(byteArrayOf(1, 2)))
        queue.close()
        assertFalse(queue.offer(byteArrayOf(3, 4)))
        assertNull(queue.take(4))
    }

    @Test
    fun playbackPositionHandlesSignedBoundaryAndUnsignedRollover() {
        val head = GptLivePlaybackHead()
        assertEquals(0x7fff_ffffL, head.update(0x7fff_ffff))
        assertEquals(0x8000_0000L, head.update(Int.MIN_VALUE))
        assertEquals(0xffff_fffeL, head.update(-2))
        assertEquals(0x1_0000_0002L, head.update(2))
    }

    @Test(expected = IllegalStateException::class)
    fun anUnexpectedDeviceResetIsNotMistakenForFourBillionPlayedFrames() {
        val head = GptLivePlaybackHead()
        head.update(24_000)
        head.update(0)
    }
}
