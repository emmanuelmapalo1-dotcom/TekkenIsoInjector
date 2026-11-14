package com.example.tekkeninjector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tekkeninjector.ui.InjectorScreen
import com.example.tekkeninjector.ui.InjectorTheme
import com.example.tekkeninjector.ui.InjectorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: InjectorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InjectorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                // A ~1 GB ISO rewrite takes a while; keep the screen (and process) alive meanwhile.
                val view = LocalView.current
                DisposableEffect(state.running) {
                    view.keepScreenOn = state.running
                    onDispose { view.keepScreenOn = false }
                }

                InjectorScreen(
                    state = state,
                    onIsoPicked = viewModel::onIsoPicked,
                    onZipPicked = viewModel::onZipPicked,
                    onOutputPicked = viewModel::startBuild,
                    onPresetSelected = viewModel::onPresetSelected,
                    onCustomIdChanged = viewModel::onCustomIdChanged,
                    onAnalyze = viewModel::analyze,
                    onExportIndex = viewModel::exportIndex,
                    onCancel = viewModel::cancel,
                )
            }
        }
    }
}
