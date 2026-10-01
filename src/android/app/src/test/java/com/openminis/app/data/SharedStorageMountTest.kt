package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test

class SharedStorageMountTest {
    @Test fun legacyFolderGrantsNeverBecomeWholeStorageGrants() {
        val settings = MountedFoldersStore.decodeSettings(
            """[{"name":"docs","treeUri":"content://example/tree/docs","userAllowWrite":true}]"""
        )
        assertFalse(settings.enabled)
    }

    @Test fun existingSharedStorageChoiceSurvivesMigration() {
        val settings = MountedFoldersStore.decodeSettings(
            """[{"name":"phone-2","isSharedStorage":true,"userAllowWrite":false}]"""
        )
        assertTrue(settings.enabled)
        assertFalse(settings.allowWrite)
    }

    @Test fun newSettingsSurviveReload() {
        val settings = MountedFoldersStore.decodeSettings("""{"enabled":true,"allowWrite":false}""")
        assertTrue(settings.enabled)
        assertFalse(settings.allowWrite)
        assertFalse(MountedFoldersStore.decodeSettings("[]").enabled)
    }

    @Test fun onlyOneFixedPathAndReadOnlyIntent() {
        val entry = MountedFoldersStore.Entry("/storage/emulated/0", isWritable = true, userAllowWrite = false)
        assertEquals("phone", entry.name)
        assertEquals("phone", entry.id)
        assertEquals("/var/minis/mounts/phone", MountedFoldersStore.LINUX_PATH)
        assertFalse(entry.effectiveWritable)
        assertFalse(entry.copy(isWritable = false, userAllowWrite = true).effectiveWritable)
    }
}
