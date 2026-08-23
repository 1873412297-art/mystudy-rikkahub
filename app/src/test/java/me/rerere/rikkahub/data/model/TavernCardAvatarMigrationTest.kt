package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TavernCardAvatarMigrationTest {
    @Test
    fun `new png import uses card image as avatar without assigning a chat background`() {
        val imported = Assistant(
            name = "Alice",
            tavernCardJson = "{}",
            background = "content://old-parser-background",
        ).withImportedTavernCardImage("content://cards/alice.png")

        assertEquals(Avatar.Image("content://cards/alice.png"), imported.avatar)
        assertNull(imported.background)
    }

    @Test
    fun `legacy Tavern card copies its background into an empty avatar and keeps the background`() {
        val legacy = Assistant(
            tavernCardJson = "{}",
            avatar = Avatar.Dummy,
            background = "content://cards/legacy.png",
        )

        val migrated = legacy.migrateLegacyTavernCardAvatar()

        assertEquals(Avatar.Image("content://cards/legacy.png"), migrated.avatar)
        assertEquals("content://cards/legacy.png", migrated.background)
    }

    @Test
    fun `migration leaves non Tavern and explicitly customized avatars unchanged`() {
        val ordinary = Assistant(background = "content://ordinary.png")
        val customized = Assistant(
            tavernCardJson = "{}",
            avatar = Avatar.Emoji("A"),
            background = "content://cards/alice.png",
        )

        assertSame(ordinary, ordinary.migrateLegacyTavernCardAvatar())
        assertSame(customized, customized.migrateLegacyTavernCardAvatar())
    }
}
