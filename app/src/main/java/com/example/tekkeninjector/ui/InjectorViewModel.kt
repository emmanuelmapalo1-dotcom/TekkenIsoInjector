package com.example.tekkeninjector.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tekkeninjector.engine.InjectorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelPreset(val key: String, val label: String, val id: String?)

val MODEL_PRESETS = listOf(
    ModelPreset("AK", "Armor King", "1425"),
    ModelPreset("KING", "King", "03"),
    ModelPreset("KAZUYA", "Kazuya", "0E"),
    ModelPreset("JIN", "Jin", "09"),
    ModelPreset("CUSTOM", "Custom ID", null),
)

private val ID_REGEX = Regex("^[0-9A-F]{1,4}$")

data class UiState(
    val isoUri: Uri? = null,
    val isoName: String? = null,
    val zipUri: Uri? = null,
    val zipName: String? = null,
    val presetKey: String = "AK",
    val customId: String = "",
    val running: Boolean = false,
    val progress: Float = 0f,
    val phase: String = "Idle",
    val log: List<String> = listOf("Select a base PSP ISO and a mod ZIP to begin."),
) {
    val targetId: String
        get() = if (presetKey == "CUSTOM") customId.trim().uppercase()
        else MODEL_PRESETS.first { it.key == presetKey }.id.orEmpty()

    val targetValid: Boolean get() = ID_REGEX.matches(targetId)
    val canBuild: Boolean get() = isoUri != null && zipUri != null && targetValid && !running
    val canAnalyze: Boolean get() = isoUri != null && !running

    fun suggestedOutputName(): String {
        val n = isoName
        val base = if (n != null) n.substringBeforeLast('.', n) else "tekken6"
        return "${base}_modded.iso"
    }
}

class InjectorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val engine = InjectorEngine(app.applicationContext)
    private var job: Job? = null

    fun onIsoPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = queryName(uri)
            _state.update { it.copy(isoUri = uri, isoName = name) }
            appendLog("Base ISO: $name")
        }
    }

    fun onZipPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = queryName(uri)
            _state.update { it.copy(zipUri = uri, zipName = name) }
            appendLog("Mod ZIP: $name")
        }
    }

    fun onPresetSelected(key: String) = _state.update { it.copy(presetKey = key) }

    fun onCustomIdChanged(text: String) {
        val cleaned = text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(4)
        _state.update { it.copy(customId = cleaned) }
    }

    fun startBuild(outUri: Uri) {
        val s = _state.value
        val iso = s.isoUri ?: return
        val zip = s.zipUri ?: return
        if (!s.targetValid) return
        launchJob(deletesOutput = true) { progress ->
            engine.run(iso, zip, outUri, s.targetId, this@InjectorViewModel::appendLog, progress)
        }
    }

    fun analyze() {
        val iso = _state.value.isoUri ?: return
        launchJob(deletesOutput = false) { progress -> engine.analyze(iso, this@InjectorViewModel::appendLog, progress) }
    }

    fun exportIndex(outUri: Uri) {
        val iso = _state.value.isoUri ?: return
        launchJob(deletesOutput = true) { progress -> engine.exportIndex(iso, outUri, this@InjectorViewModel::appendLog, progress) }
    }

    fun exportSample(outUri: Uri) {
        val iso = _state.value.isoUri ?: return
        launchJob(deletesOutput = true) { progress -> engine.exportRegionSample(iso, outUri, this@InjectorViewModel::appendLog, progress) }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun launchJob(deletesOutput: Boolean, block: suspend (progress: (Float, String) -> Unit) -> Unit) {
        if (_state.value.running) return
        _state.update { it.copy(running = true, progress = 0f, phase = "Starting", log = emptyList()) }
        job = viewModelScope.launch {
            try {
                block { p, phase -> _state.update { it.copy(progress = p, phase = phase) } }
                _state.update { it.copy(running = false, progress = 1f, phase = "Done") }
            } catch (e: CancellationException) {
                appendLog(if (deletesOutput) "Cancelled. Partial output was deleted." else "Cancelled.")
                _state.update { it.copy(running = false, phase = "Cancelled") }
                throw e
            } catch (e: Throwable) {
                appendLog("ERROR: ${e.message ?: e.javaClass.simpleName}")
                if (deletesOutput) appendLog("Output file was deleted.")
                _state.update { it.copy(running = false, phase = "Failed") }
            }
        }
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private fun queryName(uri: Uri): String {
        val name = runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "selected file"
    }

    private companion object {
        const val MAX_LOG_LINES = 3000
    }
}
