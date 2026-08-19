package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.migration.TavernPermissionCompatibilityMigration
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernPermissionMigrationTest {
    @Test
    fun `fresh preferences migrate to maximum compatibility with request headers disabled`() = runBlocking {
        val migration = TavernPermissionCompatibilityMigration()

        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val migrated = migration.migrate(emptyPreferences())

        assertEquals(
            TavernRuntimePermissions.maximumCompatible(),
            JsonInstant.decodeFromString<TavernRuntimePermissions>(
                requireNotNull(migrated[SettingsStore.TAVERN_RUNTIME_PERMISSIONS]),
            ),
        )
        assertTrue(migrated[SettingsStore.TAVERN_PERMISSION_COMPAT_MIGRATED] == true)
        assertFalse(TavernRuntimePermissions.maximumCompatible().allowRequestHeaders)
    }

    @Test
    fun `legacy stored permissions migrate once and later manual choices are preserved`() = runBlocking {
        val migration = TavernPermissionCompatibilityMigration()
        val legacy = mutablePreferencesOf(
            SettingsStore.TAVERN_RUNTIME_PERMISSIONS to
                JsonInstant.encodeToString(TavernRuntimePermissions.conservative()),
        )

        val migrated = migration.migrate(legacy)
        assertEquals(
            TavernRuntimePermissions.maximumCompatible(),
            JsonInstant.decodeFromString<TavernRuntimePermissions>(
                requireNotNull(migrated[SettingsStore.TAVERN_RUNTIME_PERMISSIONS]),
            ),
        )
        assertFalse(migration.shouldMigrate(migrated))

        val manuallyChanged = migrated.toMutablePreferences().apply {
            this[SettingsStore.TAVERN_RUNTIME_PERMISSIONS] =
                JsonInstant.encodeToString(TavernRuntimePermissions.conservative())
        }
        assertFalse(migration.shouldMigrate(manuallyChanged))
        assertEquals(
            TavernRuntimePermissions.conservative(),
            JsonInstant.decodeFromString<TavernRuntimePermissions>(
                requireNotNull(manuallyChanged[SettingsStore.TAVERN_RUNTIME_PERMISSIONS]),
            ),
        )
    }

    @Test
    fun `permission presets have exact maximum and conservative values`() {
        assertEquals(
            TavernRuntimePermissions(
                allowScripts = true,
                allowWorldWrite = true,
                allowMessageWrite = true,
                allowNetwork = true,
                allowVariablesWrite = true,
                allowEventSubscribe = true,
                allowMacroRegister = true,
                allowRequestHeaders = false,
            ),
            TavernRuntimePermissions.maximumCompatible(),
        )
        assertEquals(
            TavernRuntimePermissions(
                allowScripts = false,
                allowWorldWrite = false,
                allowMessageWrite = false,
                allowNetwork = false,
                allowVariablesWrite = false,
                allowEventSubscribe = false,
                allowMacroRegister = false,
                allowRequestHeaders = false,
            ),
            TavernRuntimePermissions.conservative(),
        )
    }
}
