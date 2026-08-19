package me.rerere.rikkahub.service.tavern

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes greeting commits and guarantees cancellation-safe rollback on any failed apply. */
internal suspend fun <Snapshot> withTavernGreetingAtomicCommit(
    mutex: Mutex,
    capture: suspend () -> Snapshot,
    apply: suspend (Snapshot) -> Unit,
    rollback: suspend (Snapshot) -> Unit,
) = mutex.withLock {
    val before = capture()
    try {
        apply(before)
    } catch (error: Throwable) {
        withContext(NonCancellable) { rollback(before) }
        throw error
    }
}
