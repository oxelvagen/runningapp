package se.oxelv.mintracker.tracking

import kotlinx.coroutines.channels.Channel

/** The channel only wakes the consumer; payloads remain owned until explicitly polled. */
internal class RecordingInbox<T>(private val signal: Channel<Unit> = Channel(Channel.CONFLATED),
    private val onSignalFailure: () -> Unit = {}) {
    private val pending = ArrayDeque<T>()
    private var accepting = true
    fun offer(value: T): Boolean {
        synchronized(pending) {
            if (!accepting) return false
            pending.addLast(value)
        }
        if (signal.trySend(Unit).isFailure) onSignalFailure()
        return true
    }
    fun poll(): T? = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() }
    suspend fun awaitSignal(): Boolean = signal.receiveCatching().isSuccess
    fun close() { synchronized(pending) { accepting = false }; signal.close() }
}
