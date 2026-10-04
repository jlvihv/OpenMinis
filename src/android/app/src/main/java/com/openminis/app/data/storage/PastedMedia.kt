package com.openminis.app.data.storage

/** Durable mediaRef discriminator shared by composing, rendering and model replay. */
object PastedMedia {
    const val PASTED_FILENAME_PREFIX = "Pasted#"
    const val MIME = "text/plain"
    const val MISSING_PLACEHOLDER = "[pasted content unavailable — the stored text file is missing]"

    fun fileNameFor(id: Int): String = "$PASTED_FILENAME_PREFIX$id.txt"

    // Both fields matter: ordinary text/plain attachments must remain attachments.
    fun isPastedRef(mimeType: String?, originalFileName: String?): Boolean =
        mimeType == MIME && originalFileName != null && originalFileName.startsWith(PASTED_FILENAME_PREFIX)
}
