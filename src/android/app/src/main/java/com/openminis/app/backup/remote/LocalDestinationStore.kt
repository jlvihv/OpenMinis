package com.openminis.app.backup.remote
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
/** Only SAF folders can be configured as backup destinations. */
class LocalDestinationStore(context: Context) {
    @Serializable data class Remote(val name: String, val backend: String = BACKEND_LOCAL,
        val params: Map<String, String> = emptyMap(), val path: String = "", val createdAt: Long = 0,
        val enabled: Boolean = true)
    class StoreException(message: String): Exception(message)
    private val prefs = context.getSharedPreferences("backup_local_destinations", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    init {
        if (!prefs.contains("remotes")) {
            val previous = context.getSharedPreferences("backup_rclone_remotes", Context.MODE_PRIVATE)
            val local = runCatching { json.decodeFromString<List<Remote>>(previous.getString("remotes", "[]")!!) }.getOrDefault(emptyList()).filter { isLocalFolder(it.backend) }
            prefs.edit().putString("remotes", json.encodeToString(local)).apply()
        }
        val folders = remotes
        val selected = folders.firstOrNull { it.enabled }
        if (folders.count { it.enabled } > 1) {
            val normalized = folders.map { it.copy(enabled = it.name == selected?.name) }
            prefs.edit().putString("remotes", json.encodeToString(normalized)).apply()
        }
    }
    var remotes: List<Remote>
        get() = runCatching { json.decodeFromString<List<Remote>>(prefs.getString("remotes", "[]")!!) }.getOrDefault(emptyList()).filter { isLocalFolder(it.backend) }
        private set(value) { prefs.edit().putString("remotes", json.encodeToString(value)).apply() }
    val enabledRemotes get() = remotes.filter { it.enabled }
    fun remote(name: String) = remotes.firstOrNull { it.name == name }
    fun addLocalFolder(name: String, treeUri: String, displayPath: String) {
        if (name.isBlank() || remote(name.trim()) != null) throw StoreException("Choose a unique folder name.")
        if (remotes.any { it.params[PARAM_TREE_URI] == treeUri }) throw StoreException("Folder already added.")
        remotes = remotes + Remote(name.trim(), params = mapOf(PARAM_TREE_URI to treeUri), path = displayPath, createdAt = System.currentTimeMillis())
    }
    /** Keep old folder records for history, but deliver only to the selected folder. */
    fun selectFolder(treeUri: String, displayPath: String) {
        val all = remotes
        val existing = all.firstOrNull { it.params[PARAM_TREE_URI] == treeUri }
        val selected = existing?.copy(path = displayPath, enabled = true) ?: Remote(
            name = java.util.UUID.randomUUID().toString(),
            params = mapOf(PARAM_TREE_URI to treeUri),
            path = displayPath,
            createdAt = System.currentTimeMillis(),
        )
        remotes = all.filterNot { it.name == selected.name }.map { it.copy(enabled = false) } + selected
    }
    fun remove(name: String) { remotes = remotes.filterNot { it.name == name } }
    fun setEnabled(name: String, on: Boolean) { remotes = remotes.map { if (it.name == name) it.copy(enabled = on) else it } }
    fun refreshLocalDestinations() { }
    companion object {
        const val BACKEND_LOCAL = "local-folder"
        const val PARAM_TREE_URI = "treeUri"
        fun isLocalFolder(backend: String) = backend == BACKEND_LOCAL
    }
}
