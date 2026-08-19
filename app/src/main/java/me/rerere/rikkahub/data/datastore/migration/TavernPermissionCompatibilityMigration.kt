package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.utils.JsonInstant

/** Applies the approved compatibility baseline exactly once on both fresh installs and upgrades. */
class TavernPermissionCompatibilityMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SettingsStore.TAVERN_PERMISSION_COMPAT_MIGRATED] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = currentData.toMutablePreferences()
        migrated[SettingsStore.TAVERN_RUNTIME_PERMISSIONS] =
            JsonInstant.encodeToString(TavernRuntimePermissions.maximumCompatible())
        migrated[SettingsStore.TAVERN_PERMISSION_COMPAT_MIGRATED] = true
        return migrated.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
