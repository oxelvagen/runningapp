package se.oxelv.mintracker.tracking

import kotlinx.coroutines.*

/** Never hold the recording actor while waiting for provider callbacks. */
internal object GpsDrain {
    suspend fun finish(flush: suspend () -> Unit, remove: suspend () -> Unit,
        callbackBarrier: suspend () -> Unit, timeoutMs: Long = 4000): Throwable? {
        var failure: Throwable? = null
        for (operation in listOf(flush, remove)) {
            try { withTimeout(timeoutMs) { operation() } }
            catch (error: TimeoutCancellationException) { failure = failure ?: error }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure = failure ?: error }
        }
        callbackBarrier()
        return failure
    }
}
