package se.oxelv.mintracker.tracking

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Conflate only redundant snapshots, never GPS events or durable pause/stop barriers. */
internal class RecordingCheckpoints(private val write: suspend (Session) -> Unit,
    private val onFailure: (Throwable) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicReference<Session?>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val worker = scope.launch {
        for (ignored in wake) {
            try { mutex.withLock { pending.getAndSet(null)?.let { write(it) } } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { onFailure(error) }
        }
    }
    fun schedule(session: Session) {
        pending.set(session)
        check(wake.trySend(Unit).isSuccess) { "Checkpoint writer closed" }
    }
    suspend fun flush(session: Session) = mutex.withLock {
        pending.set(null)
        write(session)
    }
    suspend fun close() { wake.close(); worker.join(); scope.cancel() }
    fun cancel() { wake.close(); scope.cancel() }
}
