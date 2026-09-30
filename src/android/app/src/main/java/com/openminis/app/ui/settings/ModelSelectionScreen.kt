package com.openminis.app.ui.settings

import com.openminis.app.ui.components.MinisTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ModelPickerSheet
import com.openminis.app.ui.chat.voice.VoiceInputPickerSheet
import com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.UnifiedModelPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(repo: ProviderRepository, onBack: () -> Unit, onAddAgentModels: () -> Unit) {
    val config by repo.config.collectAsState()
    var picker by remember { mutableStateOf<String?>(null) }
    fun label(id: String?): String = config.modelEntries.firstOrNull { it.id == id }
        ?.let { e ->
            val provider = config.instances.firstOrNull { it.id == e.providerInstanceId }?.label.orEmpty()
            if (provider.isBlank()) e.model.displayName else "$provider · ${e.model.displayName}"
        } ?: ""
    Scaffold(topBar = {
        MinisTopAppBar(title = { Text(stringResource(R.string.model_selection_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(stringResource(R.string.model_selection_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                SelectionRow(stringResource(R.string.model_selection_default), label(config.defaultModelEntryId)
                    .ifEmpty { stringResource(R.string.model_selection_last_used) }, { picker = "chat" })
                SelectionRow(stringResource(R.string.model_selection_title_model), label(config.titleModelEntryId)
                    .ifEmpty { stringResource(R.string.model_selection_chat_model) }, { picker = "title" })
                SelectionRow(stringResource(R.string.voice_input_picker_title),
                    repo.resolveVoiceInputChoice().entry?.second?.model?.displayName
                        ?: stringResource(R.string.model_selection_system), { picker = "input" })
                SelectionRow(stringResource(R.string.tts_capsule_picker_title),
                    repo.resolveVoiceOutputChoice().entry?.second?.model?.displayName
                        ?: stringResource(R.string.model_selection_system), { picker = "output" })
                SelectionRow(stringResource(R.string.model_selection_vision), label(config.visionModelEntryId)
                    .ifEmpty { stringResource(R.string.model_selection_disabled) }, { picker = "vision" })
                if (config.visionModelEntryId != null) {
                    TextButton(onClick = { repo.visionModelEntryId = null }) {
                        Text(stringResource(R.string.model_selection_disable_vision))
                    }
                }
                if (config.defaultModelEntryId != null) {
                    TextButton(onClick = { repo.defaultModelEntryId = null }) {
                        Text(stringResource(R.string.model_selection_last_used))
                    }
                }
                Text(stringResource(R.string.model_selection_vision_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.model_selection_agent), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                config.agentLoopModelEntryIds.forEach { id ->
                    val entry = config.modelEntries.firstOrNull { it.id == id } ?: return@forEach
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(entry.model.displayName, Modifier.weight(1f))
                        IconButton(onClick = { repo.removeAgentLoopEntry(id) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                }
                TextButton(onClick = onAddAgentModels) { Text(stringResource(R.string.model_selection_add_models)) }
            }
        }
    }
    when (picker) {
        "chat" -> ModelPickerSheet(activeEntryId = config.defaultModelEntryId, config = config,
            providerRepository = repo, onSelectEntry = { repo.defaultModelEntryId = it; picker = null },
            onDismiss = { picker = null })
        "title" -> ModelPickerSheet(activeEntryId = config.titleModelEntryId, config = config,
            providerRepository = repo, onSelectEntry = { repo.titleModelEntryId = it; picker = null },
            onDismiss = { picker = null })
        "input" -> VoiceInputPickerSheet(repo) { picker = null }
        "output" -> VoiceOutputPickerSheet(repo) { picker = null }
        "vision" -> UnifiedModelPickerSheet(providerRepository = repo,
            title = stringResource(R.string.model_selection_vision), modalityFilter = PickerModalityFilter.IMAGE_INPUT,
            selectedId = config.visionModelEntryId, onSelect = { repo.visionModelEntryId = it }, onDismiss = { picker = null })
    }
}

@Composable
private fun SelectionRow(title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
