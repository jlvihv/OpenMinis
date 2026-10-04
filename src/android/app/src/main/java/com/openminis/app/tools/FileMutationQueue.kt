package com.openminis.app.tools

import java.io.File
import kotlinx.coroutines.sync.Mutex

/** Shared by write/edit and their legacy aliases; different files can still run concurrently. */
internal object FileMutationQueue {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    suspend fun <T> withFile(file: File, action: suspend () -> T): T {
        val key = file.canonicalPath
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return action()
        } finally {
            if (acquired) entry.mutex.unlock()
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
