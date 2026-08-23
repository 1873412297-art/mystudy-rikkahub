package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TavernCardPermissionTest {
    @Test
    fun `permission fingerprint is stable and changes with card content`() {
        val first = tavernCardPermissionFingerprint("{\"name\":\"Alice\"}")

        assertEquals(first, tavernCardPermissionFingerprint("{\"name\":\"Alice\"}"))
        assertNotEquals(first, tavernCardPermissionFingerprint("{\"name\":\"Alice 2\"}"))
        assertEquals(64, first.length)
    }
}
