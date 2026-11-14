package com.example.tekkeninjector.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InjectorScreen(
    state: UiState,
    onIsoPicked: (Uri) -> Unit,
    onZipPicked: (Uri) -> Unit,
    onOutputPicked: (Uri) -> Unit,
    onPresetSelected: (String) -> Unit,
    onCustomIdChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onExportIndex: (Uri) -> Unit,
    onExportSample: (Uri) -> Unit,
    onCancel: () -> Unit,
) {
    // Storage Access Framework: no storage permission needed on Android 11+.
    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIsoPicked(uri)
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onZipPicked(uri)
    }
    val createOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-iso9660-image")
    ) { uri -> if (uri != null) onOutputPicked(uri) }
    val createIndexCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) onExportIndex(uri) }
    val createSampleCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) onExportSample(uri) }
    val clipboard = LocalClipboardManager.current

    Scaffold(topBar = { TopAppBar(title = { Text("Tekken 6 ISO Mod Injector") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilePickerRow("Select Base PSP ISO", state.isoName, !state.running) { pickIso.launch(arrayOf("*/*")) }
            FilePickerRow("Select Mod ZIP Archive", state.zipName, !state.running) { pickZip.launch(arrayOf("*/*")) }

            TargetSelector(state, onPresetSelected, onCustomIdChanged)

            Button(
                onClick = { createOutput.launch(state.suggestedOutputName()) },
                enabled = state.canBuild,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Inject & Build Modded ISO") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onAnalyze,
                    enabled = state.canAnalyze,
                    modifier = Modifier.weight(1f),
                ) { Text("Analyze ISO") }
                OutlinedButton(
                    onClick = { createIndexCopy.launch("DATA401.IDX") },
                    enabled = state.canAnalyze,
                    modifier = Modifier.weight(1f),
                ) { Text("Export IDX") }
                OutlinedButton(
                    onClick = { createSampleCopy.launch("bulk_sample.bin") },
                    enabled = state.canAnalyze,
                    modifier = Modifier.weight(1f),
                ) { Text("Raw sample") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.phase} - ${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row {
                        if (state.running) TextButton(onClick = onCancel) { Text("Cancel") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.log.joinToString("\n"))) }) {
                            Text("Copy log")
                        }
                    }
                }
            }

            LogConsole(lines = state.log, modifier = Modifier.fillMaxWidth().height(360.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelector(state: UiState, onPreset: (String) -> Unit, onCustom: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = MODEL_PRESETS.first { it.key == state.presetKey }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!state.running) expanded = it },
        ) {
            OutlinedTextField(
                value = if (current.id != null) "${current.label} (${current.id})" else current.label,
                onValueChange = {},
                readOnly = true,
                enabled = !state.running,
                label = { Text("Target Character / Model ID") },
                supportingText = { Text("Used for 001_generic_item_* parts exported without a Model ID") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MODEL_PRESETS.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(if (p.id != null) "${p.label} (${p.id})" else p.label) },
                        onClick = {
                            onPreset(p.key)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (state.presetKey == "CUSTOM") {
            OutlinedTextField(
                value = state.customId,
                onValueChange = onCustom,
                label = { Text("Custom Model ID (1-4 hex digits)") },
                singleLine = true,
                isError = !state.targetValid,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FilePickerRow(label: String, selected: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
        Text(
            text = selected ?: "No file selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun LogConsole(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
            items(lines) { line ->
                val color = when {
                    line.startsWith("ERROR") || line.startsWith("VERIFY FAIL") -> MaterialTheme.colorScheme.error
                    line.startsWith("WARN") || line.startsWith("SLOT") -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(text = line, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
