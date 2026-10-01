package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** One optional phone shared-storage mount. The existing store name is retained for callers. */
class MountedFoldersStore(private val context: Context) {
    data class Entry(val resolvedHostPath: String?, val isWritable: Boolean, val userAllowWrite: Boolean) {
        val id: String get() = "phone"
        val name: String get() = "phone"
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    @Serializable
    internal data class Settings(val enabled: Boolean = false, val allowWrite: Boolean = true)

    private val storeFile = File(context.filesDir, "minis-config/mounted-folders.json")
    private val mutex = Mutex()
    private var settings = Settings()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    var onChange: (() -> Unit)? = null

    init {
        runCatching {
            if (storeFile.isFile) {
                val text = storeFile.readText()
                settings = decodeSettings(text)
                if (JSON.parseToJsonElement(text) is JsonArray) {
                    save(settings)
                    releaseLegacyGrants(text)
                }
            }
            _entries.value = snapshot()
        }.onFailure { AppLogger.warning("SharedStorage", "Cannot load mount settings: ${it.message}") }
    }

    suspend fun addSharedStorage(userAllowWrite: Boolean = true): Entry? = mutex.withLock {
        if (sharedStorageRoot(context) == null) return@withLock null
        update(Settings(enabled = true, allowWrite = userAllowWrite))
        _entries.value.firstOrNull()
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        if (id != "phone" || !settings.enabled) return@withLock false
        update(settings.copy(enabled = false))
        true
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        if (id != "phone" || !settings.enabled || settings.allowWrite == allow) return@withLock false
        update(settings.copy(allowWrite = allow))
        true
    }

    suspend fun refreshWritability() = mutex.withLock {
        val next = withContext(Dispatchers.IO) { snapshot() }
        if (next != _entries.value) {
            _entries.value = next
            onChange?.invoke()
        }
    }

    private suspend fun update(next: Settings) {
        withContext(Dispatchers.IO) { save(next) }
        settings = next
        _entries.value = withContext(Dispatchers.IO) { snapshot() }
        onChange?.invoke()
    }

    private fun snapshot(): List<Entry> {
        if (!settings.enabled) return emptyList()
        val root = sharedStorageRoot(context)
        return listOf(Entry(root, root?.let { probeWritable(it) } ?: false, settings.allowWrite))
    }

    private fun save(next: Settings) {
        check(storeFile.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Cannot create mount settings directory" }
        val temporary = File(storeFile.parentFile, "${storeFile.name}.tmp")
        temporary.writeText(JSON.encodeToString(next))
        check(temporary.renameTo(storeFile)) { "Cannot save mount settings" }
    }

    private fun releaseLegacyGrants(text: String) {
        (JSON.parseToJsonElement(text) as JsonArray).forEach { item ->
            val uri = item.jsonObject["treeUri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (uri.isNotBlank()) runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun probeWritable(host: String): Boolean {
        val probe = File(host, ".minis-probe-${UUID.randomUUID()}")
        return try {
            probe.outputStream().use { it.write(0) }
            true
        } catch (_: Exception) { false }
        finally { probe.delete() }
    }

    companion object {
        const val LINUX_PATH = "/var/minis/mounts/phone"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun decodeSettings(text: String): Settings {
            val data = JSON.parseToJsonElement(text)
            if (data !is JsonArray) return JSON.decodeFromString<Settings>(text)
            // Never widen a former folder-only grant into whole-storage access.
            val shared = data.firstOrNull {
                it.jsonObject["isSharedStorage"]?.jsonPrimitive?.booleanOrNull == true
            }?.jsonObject ?: return Settings()
            return Settings(enabled = true,
                allowWrite = shared["userAllowWrite"]?.jsonPrimitive?.booleanOrNull ?: true)
        }

        @Suppress("DEPRECATION")
        fun sharedStorageRoot(context: Context): String? {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return null
            return Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory && it.canRead() }?.absolutePath
        }
    }
}
