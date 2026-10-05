package com.openminis.app.data.db

/** One transaction's transcript, compact marker and accounting calibration records. */
data class RuntimeHistorySnapshot(val messages: List<MessageEntity>, val marker: CompactMarkerEntity?,
    val usages: List<String>)
