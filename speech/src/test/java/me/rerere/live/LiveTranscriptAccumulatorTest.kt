package me.rerere.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptAccumulatorTest {
    @Test
    fun preservesOverlappingSpeakersWithoutMixingTheirWords() {
        val transcript = LiveTranscriptAccumulator()
        transcript.append(LiveTranscript(LiveSpeaker.User, "你好", 0, 400))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "你好，", 300, 700))
        transcript.append(LiveTranscript(LiveSpeaker.User, "，请说中文。", 400, 900))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "可以的。", 700, 1200))
        assertEquals(listOf("你好，请说中文。", "你好，可以的。"), transcript.snapshot().map { it.text })
        assertEquals(listOf(LiveSpeaker.User, LiveSpeaker.Assistant), transcript.snapshot().map { it.speaker })
    }

    @Test
    fun shortBackAndForthRepliesRemainSeparate() {
        val transcript = LiveTranscriptAccumulator()
        transcript.append(LiveTranscript(LiveSpeaker.User, "你好", 0, 500))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "你好", 500, 1000))
        transcript.append(LiveTranscript(LiveSpeaker.User, "谢谢", 1000, 1500))
        assertEquals(listOf("你好", "你好", "谢谢"), transcript.snapshot().map { it.text })
    }

    @Test
    fun pausesSplitUtterancesAndEmptyFragmentsDoNotCreateMessages() {
        val transcript = LiveTranscriptAccumulator()
        assertTrue(transcript.snapshot().isEmpty())
        transcript.append(LiveTranscript(LiveSpeaker.User, "第一句", 100, 600))
        transcript.append(LiveTranscript(LiveSpeaker.User, "第二句", 3000, 4000))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, " ", 4500, 4500))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "", 4500, 4500))
        assertEquals(listOf("第一句", "第二句"), transcript.snapshot().map { it.text })
    }

    @Test
    fun timestampsOrderDifferentSpeakersEvenWhenTranscriptsArriveLate() {
        val transcript = LiveTranscriptAccumulator()
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "回答", 2000, 2500))
        transcript.append(LiveTranscript(LiveSpeaker.User, "问题", 100, 500))
        val firstSnapshot = transcript.snapshot()
        transcript.append(LiveTranscript(LiveSpeaker.User, "？", 500, 600))
        assertEquals(listOf("问题", "回答"), firstSnapshot.map { it.text })
        assertEquals(listOf("问题？", "回答"), transcript.snapshot().map { it.text })
    }
}
