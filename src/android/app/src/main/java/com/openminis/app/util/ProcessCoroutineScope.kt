package com.openminis.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Explicit process lifetime for OAuth callbacks and shell watchdogs which must
 * outlive a screen. Shell callers retain and cancel their individual jobs.
 * SupervisorJob prevents one failed callback from cancelling other operations.
 */
internal val processCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
