package com.openminis.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisTopAppBar
import kotlinx.coroutines.launch

/** Single shared-storage mount: no folder picker, names or per-folder configuration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountedFoldersScreen(store: MountedFoldersStore, onBack: () -> Unit, onBrowseFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val entry = entries.firstOrNull()
    var hasAccess by remember { mutableStateOf(MountedFoldersStore.sharedStorageRoot(context) != null) }
    var allowWrite by remember(entry?.userAllowWrite) { mutableStateOf(entry?.userAllowWrite ?: true) }
    var showConfirm by remember { mutableStateOf(false) }
    var showUnmount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = MountedFoldersStore.sharedStorageRoot(context) != null
                scope.launch { store.refreshWritability() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = MountedFoldersStore.sharedStorageRoot(context) != null
        scope.launch { store.refreshWritability() }
    }
    val grantAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"))) }
                    .onFailure { error = context.getString(R.string.mount_shared_storage_failed) }
            }
        } else legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    Scaffold(topBar = {
        MinisTopAppBar(title = { Text(stringResource(R.string.mount_shared_storage)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.mount_shared_storage_message, MountedFoldersStore.LINUX_PATH))
            Text(MountedFoldersStore.LINUX_PATH, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mount_add_allow_writes), Modifier.weight(1f))
                Switch(checked = allowWrite, onCheckedChange = { value ->
                    allowWrite = value
                    if (entry != null) scope.launch {
                        runCatching { store.setUserAllowWrite(entry.id, value) }
                            .onFailure { error = context.getString(R.string.mount_shared_storage_failed) }
                    }
                })
            }
            if (!hasAccess) {
                Text(stringResource(R.string.mount_all_files_access_required), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = grantAccess) { Text(stringResource(R.string.mount_shared_storage_grant)) }
            }
            if (entry == null) {
                Button(onClick = { showConfirm = true }) { Text(stringResource(R.string.mount_shared_storage_confirm)) }
            } else {
                OutlinedButton(onClick = onBrowseFiles, enabled = entry.resolvedHostPath != null) {
                    Text(stringResource(R.string.mount_detail_browse_files))
                }
                OutlinedButton(onClick = { showUnmount = true }) { Text(stringResource(R.string.mount_unmount_confirm)) }
            }
        }
    }
    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false },
        title = { Text(stringResource(R.string.mount_shared_storage)) },
        text = { Text(stringResource(R.string.mount_shared_storage_message, MountedFoldersStore.LINUX_PATH)) },
        confirmButton = {
            MinisTextButton(onClick = {
                if (!hasAccess) grantAccess()
                else scope.launch {
                    runCatching { store.addSharedStorage(allowWrite) }
                        .onSuccess { if (it == null) error = context.getString(R.string.mount_shared_storage_failed) }
                        .onFailure { error = context.getString(R.string.mount_shared_storage_failed) }
                    showConfirm = false
                }
            }) { Text(stringResource(if (hasAccess) R.string.mount_shared_storage_confirm else R.string.mount_shared_storage_grant)) }
        },
        dismissButton = { MinisTextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showUnmount) AlertDialog(
        onDismissRequest = { showUnmount = false },
        title = { Text(stringResource(R.string.mount_unmount_title)) },
        text = { Text(stringResource(R.string.mount_unmount_message)) },
        confirmButton = {
            MinisTextButton(onClick = {
                scope.launch {
                    runCatching { store.remove("phone") }.onFailure { error = context.getString(R.string.mount_shared_storage_failed) }
                    showUnmount = false
                }
            }) { Text(stringResource(R.string.mount_unmount_confirm)) }
        },
        dismissButton = { MinisTextButton(onClick = { showUnmount = false }) { Text(stringResource(R.string.cancel)) } },
    )
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = {
            MinisTextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) }
        })
    }
}
