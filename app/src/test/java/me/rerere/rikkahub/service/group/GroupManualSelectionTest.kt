package me.rerere.rikkahub.service.group

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupManualSelectionTest {
    @Test
    fun `sanitizeManualSelection removes unavailable ids and preserves order`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()

        val result = sanitizeManualSelection(
            selectedIds = listOf(c, a, b),
            availableIds = listOf(a, c),
        )

        assertEquals(listOf(c, a), result)
    }

    @Test
    fun `toggleManualSelection appends new ids and removes existing ids`() {
        val a = Uuid.random()
        val b = Uuid.random()

        assertEquals(listOf(a, b), toggleManualSelection(listOf(a), b))
        assertEquals(emptyList<Uuid>(), toggleManualSelection(listOf(a), a))
    }

    @Test
    fun `moveManualSelection changes selected order`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()

        val result = moveManualSelection(listOf(a, b, c), fromIndex = 2, toIndex = 0)

        assertEquals(listOf(c, a, b), result)
    }
}
