package com.openminis.app.sandbox

import com.openminis.app.data.MountedFoldersStore

/** Read-only guards and bind spec for the single phone shared-storage mount. */
object MountedFolderCoordinator {
    fun requireWritable(linuxPath: String, store: MountedFoldersStore) {
        if (isLinuxPathUnderReadOnlyMount(linuxPath, store)) throw ReadOnlyMountException(linuxPath)
    }

    fun isLinuxPathUnderReadOnlyMount(linuxPath: String, store: MountedFoldersStore): Boolean {
        val root = MountedFoldersStore.LINUX_PATH
        if (linuxPath != root && !linuxPath.startsWith("$root/")) return false
        return store.entries.value.firstOrNull()?.effectiveWritable == false
    }

    fun bindMountSpecs(store: MountedFoldersStore): List<Pair<String, String>> {
        val host = store.entries.value.firstOrNull()?.resolvedHostPath ?: return emptyList()
        return listOf(MountedFoldersStore.LINUX_PATH to host)
    }
}

class ReadOnlyMountException(val linuxPath: String) : Exception(
    "$linuxPath is inside read-only phone storage. Enable writes in Settings → Phone shared storage.",
)
