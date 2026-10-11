package se.oxelv.mintracker.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.tracking.*
import android.os.SystemClock

class RecordingPipelineTest {
    @Test fun fullOrClosedWakeChannelNeverOwnsOrDropsGpsPayloads() = runBlocking {
        for (closed in listOf(false, true)) {
            val signal = Channel<Unit>(1)
            if (closed) signal.close()
            var failures = 0
            val inbox = RecordingInbox<Int>(signal) { failures++ }
            repeat(100) { assertTrue(inbox.offer(it)) }
            assertTrue(failures > 0)
            assertEquals((0 until 100).toList(), generateSequence { inbox.poll() }.toList())
            assertNull(inbox.poll())
            inbox.close()
            assertFalse("Explicit shutdown must reject rather than pretend to accept", inbox.offer(101))
        }
    }
    @Test fun slowCheckpointDoesNotBlockGpsAndStopCannotBeOverwrittenByOlderSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<Session>())
        val writer = RecordingCheckpoints(write = {
            if (!entered.isCompleted) { entered.complete(Unit); release.await() }
            writes += it
        }, onFailure = { throw AssertionError(it) })
        val base = Session(ownerUserId = "synthetic-owner")
        try {
            writer.schedule(base)
            withTimeout(2000) { entered.await() }
            val started = SystemClock.elapsedRealtime()
            repeat(1000) { writer.schedule(base.copy(distanceM = it.toDouble())) }
            assertTrue("Scheduling must not wait for blocked Room I/O", SystemClock.elapsedRealtime() - started < 1000)
            assertFalse(release.isCompleted)
            release.complete(Unit)
            val stopped = base.copy(status = "STOPPING", distanceM = 999.0)
            writer.flush(stopped)
            writer.close()
            assertEquals(stopped, writes.last())
            assertTrue("Conflation bounds redundant checkpoint work", writes.size <= 3)
        } finally { release.complete(Unit) }
    }
    @Test fun flushDeliversBeforeFinalBarrierAndRemoveRunsEvenOnTimeout() = runBlocking {
        val events = mutableListOf<String>()
        val result = GpsDrain.finish(flush = { events += "batch" }, remove = { events += "remove" },
            callbackBarrier = { events += "barrier" })
        assertNull(result)
        assertEquals(listOf("batch", "remove", "barrier"), events)
        events.clear()
        val timedOut = GpsDrain.finish(flush = { delay(1000) }, remove = { events += "remove" },
            callbackBarrier = { events += "barrier" }, timeoutMs = 20)
        assertTrue(timedOut is TimeoutCancellationException)
        assertEquals(listOf("remove", "barrier"), events)
    }
}
