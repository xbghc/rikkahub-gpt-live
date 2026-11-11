package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDelegationCoordinatorTest {
    @Test
    fun `caption deduplication preserves spaces and repeated words`() {
        val ledger = LiveTranscriptLedger()
        assertTrue(ledger.append("user", "very ", "a", 0.5, 20.2))
        assertFalse(ledger.append("user", "very ", "a", 0.5, 20.2))
        assertTrue(ledger.append("user", "very good", "b", 20.0, 40.0))
        assertTrue(ledger.append("assistant", "Yes.", "c", 20.5, 35.0))
        assertEquals("very very good", ledger.text("user"))
        assertEquals("Yes.", ledger.text("assistant"))
    }

    @Test
    fun `a caption overlapping delegation time remains complete`() {
        val ledger = LiveTranscriptLedger()
        ledger.append("user", "Thursday, not Friday", "a", 90.0, 110.0)
        assertTrue(ledger.request(0, 100.0)!!.contains("Thursday, not Friday"))
    }

    @Test
    fun `bounded settlement includes delayed captions without inventing a watermark`() = runTest {
        val requests = mutableListOf<String>()
        val delivered = mutableListOf<Pair<String, String>>()
        val coordinator = LiveDelegationCoordinator(this, {
            requests += it
            CompletableDeferred("Done")
        }, { id, text -> delivered += id to text }, {}, {})
        coordinator.transcript.append("user", "Meet me on ", "a", 0.0, 80.0)
        coordinator.delegate("task", 100.0)
        runCurrent()
        advanceTimeBy(300)
        coordinator.transcript.append("user", "Thursday", "b", 80.0, 120.0)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("Meet me on Thursday"))
        assertEquals(listOf("task" to "Done"), delivered)
    }

    @Test
    fun `duplicate delegation ids and revisions do not repeat backend actions`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<String?>()
        val delivered = mutableListOf<String>()
        val coordinator = LiveDelegationCoordinator(this, {
            calls++
            answer
        }, { id, _ -> delivered += id }, {}, {})
        coordinator.transcript.append("user", "Create one reminder", "a", 0.0, 80.0)
        coordinator.delegate("one", 100.0)
        coordinator.delegate("one", 100.0)
        coordinator.delegate("duplicate", 110.0)
        advanceTimeBy(601)
        runCurrent()
        assertEquals(1, calls)
        answer.complete("Created")
        runCurrent()
        assertEquals(setOf("one", "duplicate"), delivered.toSet())
    }

    @Test
    fun `distinct requests retain their own result even when replies finish out of order`() = runTest {
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>()
        var calls = 0
        val delivered = mutableListOf<Pair<String, String>>()
        val coordinator = LiveDelegationCoordinator(
            this,
            { if (calls++ == 0) first else second },
            { id, text -> delivered += id to text },
            {},
            {}
        )
        coordinator.transcript.append("user", "First request", "a", 0.0, 50.0)
        coordinator.delegate("first", 60.0)
        advanceTimeBy(601)
        coordinator.transcript.append("user", "Second request", "b", 100.0, 150.0)
        coordinator.delegate("second", 160.0)
        advanceTimeBy(601)
        second.complete("Second result")
        runCurrent()
        first.complete("First result")
        runCurrent()
        assertEquals(listOf("second" to "Second result", "first" to "First result"), delivered)
    }

    @Test
    fun `cancel revokes late delivery and will not rerun the same speech`() = runTest {
        var calls = 0
        val answer = CompletableDeferred<String?>()
        val delivered = mutableListOf<String>()
        val coordinator = LiveDelegationCoordinator(this, {
            calls++
            answer
        }, { _, text -> delivered += text }, {}, {})
        coordinator.transcript.append("user", "Old request", "a", 0.0, 50.0)
        coordinator.delegate("old", 60.0)
        advanceTimeBy(601)
        coordinator.cancelPending()
        answer.complete("Stale success")
        runCurrent()
        assertTrue(delivered.isEmpty())
        coordinator.delegate("retry-without-new-speech", 100.0)
        advanceTimeBy(601)
        assertEquals(1, calls)
        assertTrue(delivered.single().contains("cancelled"))
    }

    @Test
    fun `cancelling unsettled captions excludes them from the next request`() = runTest {
        val requests = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val coordinator = LiveDelegationCoordinator(this, {
            requests += it
            CompletableDeferred("Done")
        }, { id, _ -> delivered += id }, {}, {})
        coordinator.transcript.append("user", "Create a reminder", "old", 0.0, 50.0)
        coordinator.delegate("cancelled", 60.0)
        advanceTimeBy(300)
        coordinator.cancelPending()
        coordinator.transcript.append("user", "What time is it?", "new", 100.0, 150.0)
        coordinator.delegate("current", 160.0)
        advanceTimeBy(601)
        runCurrent()

        assertEquals(1, requests.size)
        val latestSpeech = requests.single().substringAfter("Latest user speech:")
            .substringBefore("Recent spoken context")
        assertTrue(latestSpeech.contains("What time is it?"))
        assertFalse(latestSpeech.contains("Create a reminder"))
        assertEquals(listOf("current"), delivered)
    }

    @Test
    fun `closing a coordinator detaches old replies while the new session works`() = runTest {
        val oldReply = CompletableDeferred<String?>()
        val spoken = mutableListOf<String>()
        val old = LiveDelegationCoordinator(this, { oldReply }, { _, text -> spoken += text }, {}, {})
        old.transcript.append("user", "Old", "a", 0.0, 10.0)
        old.delegate("same-id", 10.0)
        advanceTimeBy(601)
        old.close()
        val current =
            LiveDelegationCoordinator(this, { CompletableDeferred("Current") }, { _, text -> spoken += text }, {}, {})
        current.transcript.append("user", "New", "a", 0.0, 10.0)
        current.delegate("same-id", 10.0)
        oldReply.complete("Old")
        advanceTimeBy(601)
        assertEquals(listOf("Current"), spoken)
    }

    @Test
    fun `missing transcript has a bounded clarification path and makes no backend call`() = runTest {
        val messages = mutableListOf<String>()
        val coordinator = LiveDelegationCoordinator(
            this,
            { error("Must not invent a request") },
            { _, text -> messages += text },
            {},
            {}
        )
        coordinator.delegate("empty", 100.0)
        advanceTimeBy(1_201)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("clarification"))
    }
}
