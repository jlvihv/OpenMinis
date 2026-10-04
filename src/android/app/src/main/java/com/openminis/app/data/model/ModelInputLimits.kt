package com.openminis.app.data.model

import kotlinx.serialization.Serializable

/** Optional per-model inline-image profile; absent values use Pi's defaults. */
@Serializable
data class ModelInputLimits(val images: ModelImageLimits? = null)

@Serializable
data class ModelImageLimits(val resize: ModelImageResizeOptions? = null)

@Serializable
data class ModelImageResizeOptions(
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    /** Base64 payload bytes, not binary file bytes. */
    val maxBytes: Int? = null,
    val jpegQuality: Int? = null,
)
