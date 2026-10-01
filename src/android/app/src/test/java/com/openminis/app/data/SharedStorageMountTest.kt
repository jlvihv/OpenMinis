package com.openminis.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SharedStorageMountTest {
    private val json = Json { encodeDefaults = true }

    @Test fun existingSafMountsRemainCompatible() {
        val entry = json.decodeFromString<MountedFoldersStore.Entry>(
            """{"name":"docs","sourceDisplayName":"Documents","treeUri":"content://example/tree/docs"}"""
        )
        assertFalse(entry.isSharedStorage)
        assertTrue(entry.effectiveWritable)
    }

    @Test fun sharedStorageChoiceAndReadOnlyIntentSurviveReload() {
        val entry = MountedFoldersStore.Entry(name = "phone", sourceDisplayName = "/storage/emulated/0",
            treeUri = "", isSharedStorage = true, resolvedHostPath = "/storage/emulated/0",
            isWritable = true, userAllowWrite = false)
        val restored = json.decodeFromString<MountedFoldersStore.Entry>(json.encodeToString(entry))
        assertTrue(restored.isSharedStorage)
        assertEquals(entry.id, restored.id)
        assertFalse(restored.effectiveWritable)
        assertEquals(entry.resolvedHostPath, restored.resolvedHostPath)
    }

    @Test fun sharedStorageNameDoesNotClashWithPickedFolders() {
        assertEquals("phone", MountedFoldersStore.sharedStorageMountName(emptyList()))
        assertEquals("phone-2", MountedFoldersStore.sharedStorageMountName(listOf("PHONE")))
        assertEquals("phone-3", MountedFoldersStore.sharedStorageMountName(listOf("phone", "Phone-2")))
    }
}
