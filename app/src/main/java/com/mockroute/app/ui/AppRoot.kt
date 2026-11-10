package com.mockroute.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mockroute.app.setup.SetupIntents

private enum class Screen { Map, Wizard, Settings, Plan }

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editorState by vm.editor.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf<Screen?>(null) }

    val loaded = settings
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LaunchedEffect(Unit) {
        if (screen == null) screen = if (loaded.wizardCompleted) Screen.Map else Screen.Wizard
    }

    when (screen) {
        Screen.Wizard -> {
            BackHandler(enabled = loaded.wizardCompleted) { screen = Screen.Map }
            SetupWizardScreen(vm, onDone = {
                vm.completeWizard()
                screen = Screen.Map
            })
        }
        Screen.Settings -> {
            BackHandler { screen = Screen.Map }
            SettingsScreen(vm, onBack = { screen = Screen.Map }, onCheckSetup = { screen = Screen.Wizard })
        }
        Screen.Plan -> {
            BackHandler { screen = Screen.Map }
            PlanScreen(vm, onBack = { screen = Screen.Map }, indoor = editorState.mode == EditMode.Indoor)
        }
        Screen.Map -> MapScreen(vm, onOpenSettings = { screen = Screen.Settings }, onOpenPlan = {
            vm.ensurePlan()
            screen = Screen.Plan
        })
        null -> Unit
    }

    CommonDialogs(vm)
}

@Composable
private fun CommonDialogs(vm: MainViewModel) {
    val context = LocalContext.current
    val notMockApp by vm.notMockAppDialog.collectAsStateWithLifecycle()
    val disclaimer by vm.disclaimerDialog.collectAsStateWithLifecycle()

    if (notMockApp) {
        AlertDialog(
            onDismissRequest = vm::dismissNotMockAppDialog,
            title = { Text("MockRoute isn't the mock location app") },
            text = {
                Text(
                    "Android only lets the selected mock location app change your location.\n\n" +
                        "Settings → Additional settings → Developer options → Select mock location app → MockRoute",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissNotMockAppDialog()
                    SetupIntents.openDeveloperOptions(context)
                }) { Text("Open Developer options") }
            },
            dismissButton = { TextButton(onClick = vm::dismissNotMockAppDialog) { Text("Later") } },
        )
    }
    if (disclaimer) {
        DisclaimerDialog(onAccept = { vm.acceptDisclaimer() }, onDismiss = vm::dismissDisclaimerDialog)
    }
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you simulate") },
        text = { Text(DISCLAIMER_TEXT) },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

const val DISCLAIMER_TEXT =
    "Other apps can detect mock locations (Android marks every simulated fix with Location.isMock), and some " +
        "apps refuse to work or flag your account when they see one. Use MockRoute for testing and privacy only."
