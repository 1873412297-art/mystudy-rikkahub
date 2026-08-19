package me.rerere.rikkahub.service.tavern

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Test

class TavernGreetingAtomicCommitTest {
    @Test
    fun `cancellation rolls back every staged store before returning`() = runBlocking {
        val state = mutableListOf("before-a", "before-b")
        val entered = CompletableDeferred<Unit>()
        val commit = async {
            withTavernGreetingAtomicCommit(
                mutex = Mutex(),
                capture = { state.toList() },
                apply = {
                    state[0] = "after-a"
                    entered.complete(Unit)
                    awaitCancellation()
                },
                rollback = { before ->
                    state.clear()
                    state.addAll(before)
                },
            )
        }

        entered.await()
        commit.cancelAndJoin()

        assertEquals(listOf("before-a", "before-b"), state)
    }

    @Test
    fun `shared mutex serializes commits`() = runBlocking {
        val mutex = Mutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            withTavernGreetingAtomicCommit(
                mutex = mutex,
                capture = { Unit },
                apply = {
                    order += "first-enter"
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    order += "first-exit"
                },
                rollback = {},
            )
        }
        firstEntered.await()
        val second = async {
            withTavernGreetingAtomicCommit(
                mutex = mutex,
                capture = { Unit },
                apply = { order += "second-enter" },
                rollback = {},
            )
        }

        assertEquals(listOf("first-enter"), order)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
    }
}
