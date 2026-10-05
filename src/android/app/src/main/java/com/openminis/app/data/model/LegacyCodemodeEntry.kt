package com.openminis.app.data.model

import org.json.JSONArray

/**
 * Recognises `codemode-store` rows persisted by the removed codemode feature.
 *
 * Nothing writes this any more — the feature is gone. But sessions that ran
 * codemode still carry these rows, and they are transcript metadata rather than
 * messages: dropping the recogniser would replay them to the model as ordinary
 * turns and corrupt those histories. So the tag stays, read-only, until such
 * rows are gone from the database.
 */
object LegacyCodemodeEntry {
    private const val TYPE = "codemode-store"

    fun isEntry(partsJson: String): Boolean = partsJson.contains(TYPE) &&
        runCatching { JSONArray(partsJson).optJSONObject(0)?.optString("type") == TYPE }.getOrDefault(false)
}
