package com.mockroute.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockroute.app.setup.SetupChecks
import com.mockroute.app.setup.SetupIntents

private enum class Check { Ok, Missing, Info }

@Composable
fun SetupWizardScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val status by vm.setup.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var permissionAsked by remember { mutableStateOf(false) }

    // Live checks: re-evaluate every time the user comes back from a Settings screen.
    LifecycleResumeEffect(settings?.disclaimerAccepted) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionAsked = true
        vm.refreshSetup()
        if (it.values.any { granted -> granted }) vm.captureRealLocation()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshSetup()
    }
    val locationPermanentlyDenied = permissionAsked && !status.locationPermission &&
        (context as? Activity)?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, SetupChecks.locationPermissions[0])
        } == true

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set up MockRoute", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Each step checks itself live. Come back here after changing a setting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                number = 1,
                title = "Developer options",
                state = if (status.developerOptions) Check.Ok else Check.Missing,
                body = "Settings → About phone → tap \"OS version\" 7 times until \"You are now a developer\".",
            ) {
                if (!status.developerOptions) {
                    Button(onClick = { SetupIntents.openAboutPhone(context) }) { Text("Open About phone") }
                }
                OutlinedButton(onClick = { SetupIntents.openDeveloperOptions(context) }) { Text("Open Developer options") }
            }

            StepCard(
                number = 2,
                title = "Select MockRoute as mock location app",
                state = if (status.mockAppSelected) Check.Ok else Check.Missing,
                body = "Settings → Additional settings → Developer options → Select mock location app → MockRoute",
            ) {
                Button(onClick = { SetupIntents.openDeveloperOptions(context) }) { Text("Open Developer options") }
            }

            StepCard(
                number = 3,
                title = "Location & notification permissions",
                state = if (status.locationPermission && status.notificationPermission) Check.Ok else Check.Missing,
                body = "Location: MockRoute reads your REAL position before simulating, so routes start where you are. " +
                    "Choose \"Precise\" and \"While using the app\".\n" +
                    "Notifications: the ongoing notification shows the simulation and has Pause / Stop buttons.",
            ) {
                if (!status.locationPermission) {
                    if (locationPermanentlyDenied) {
                        Button(onClick = { SetupIntents.openAppDetails(context) }) { Text("Open app permissions") }
                    } else {
                        Button(onClick = { locationLauncher.launch(SetupChecks.locationPermissions) }) { Text("Allow location") }
                    }
                }
                val notif = SetupChecks.notificationPermission
                if (!status.notificationPermission && notif != null) {
                    OutlinedButton(onClick = { notificationLauncher.launch(notif) }) { Text("Allow notifications") }
                }
            }

            StepCard(
                number = 4,
                title = "Battery & background (HyperOS)",
                state = if (status.batteryUnrestricted) Check.Ok else Check.Missing,
                body = "So HyperOS doesn't kill the simulation:\n" +
                    "• Allow \"ignore battery optimizations\" (button below).\n" +
                    "• App info → Autostart: ON.\n" +
                    "• App info → Battery saver (\"Power\") → No restrictions.\n" +
                    "• Open Recents and lock MockRoute (long-press or pull down the card → lock).\n" +
                    "• Avoid Ultra battery saver while simulating.",
            ) {
                if (!status.batteryUnrestricted) {
                    Button(onClick = { SetupIntents.requestIgnoreBatteryOptimizations(context) }) {
                        Text("Ignore battery optimizations")
                    }
                }
                OutlinedButton(onClick = { SetupIntents.openAppDetails(context) }) { Text("Open app info") }
            }

            StepCard(
                number = 5,
                title = "Tip: position flickers back to the real one?",
                state = Check.Info,
                body = "Turn off Wi-Fi scanning and Bluetooth scanning (Location → Location services) and " +
                    "Google Location Accuracy. Otherwise Google services may mix real fixes in.",
            ) {
                OutlinedButton(onClick = { SetupIntents.openLocationSettings(context) }) { Text("Open Location settings") }
            }

            StepCard(
                number = 6,
                title = "Mock locations are detectable",
                state = if (status.disclaimerAccepted) Check.Ok else Check.Missing,
                body = DISCLAIMER_TEXT,
            ) {
                if (!status.disclaimerAccepted) {
                    Button(onClick = { vm.acceptDisclaimer() }) { Text("I understand") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = onDone, enabled = status.requiredOk, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.allOk) "All set — open the map" else "Continue to map")
            }
            if (!status.requiredOk) {
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip for now")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    number: Int,
    title: String,
    state: Check,
    body: String,
    actions: @Composable () -> Unit,
) {
    val (badgeColor, badgeText) = when (state) {
        Check.Ok -> Color(0xFF1E8E3E) to "✓"
        Check.Missing -> Color(0xFFD93025) to "!"
        Check.Info -> Color(0xFF5F6368) to "i"
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state == Check.Ok) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(28.dp)) {
                    Text(
                        badgeText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 3.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Text(
                    "$number. $title",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                actions()
            }
        }
    }
}
