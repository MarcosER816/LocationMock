package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.mockroute.app.net.RoutingChoice
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockroute.app.BuildConfig
import com.mockroute.app.map.TileStyle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun RoutingSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val choice = RoutingChoice.fromId(s.routingProvider)
    Text("Routing", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        RoutingChoice.entries.forEach { c ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = c == choice, onClick = { vm.setRoutingProvider(c.id) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = c == choice, onClick = null)
                Text(c.label, Modifier.padding(start = 8.dp))
            }
        }
    }
    KeyField(
        label = "OpenRouteService API key (optional)",
        initial = s.orsApiKey,
        status = vm.keyStatus(s.orsApiKey),
        onChange = vm::setOrsKey,
    )
    KeyField(
        label = "GraphHopper API key (optional)",
        initial = s.graphHopperApiKey,
        status = vm.keyStatus(s.graphHopperApiKey),
        onChange = vm::setGraphHopperKey,
    )
    Text("Traffic (Car/Bus)", style = MaterialTheme.typography.titleMedium)
    KeyField(
        label = "TomTom API key (traffic)",
        initial = s.tomTomApiKey,
        status = vm.keyStatus(s.tomTomApiKey),
        onChange = vm::setTomTomKey,
    )
    Text(
        "TomTom free tier: 2,500 requests/day (shared by all TomTom APIs). One traffic analysis uses ≤ 12 and is " +
            "cached 7 days per weekday + 15-min slot. Get a key at developer.tomtom.com. Google Routes: not used yet.",
        style = MaterialTheme.typography.bodySmall,
    )
    val test by vm.routingTest.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = vm::testRouting) { Text("Test routing") }
        test?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
    }
    Text(
        "OpenRouteService (HeiGIT, api.heigit.org): free key at openrouteservice.org — ~2,000 directions/day, " +
            "40/min. GraphHopper free: 500 credits/day, non-commercial. Keys are stored only on this phone. Without a key MockRoute draws straight lines. " +
            "Routes are cached on the phone for 30 days.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = vm::clearRouteCache) { Text("Clear route cache (${vm.routeCache.entryCount()})") }
}

/** Saved on the phone as you type (no Save button to forget). Keys never leave the device except to the provider. */
@Composable
private fun KeyField(label: String, initial: String, status: String, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        supportingText = { Text(status) },
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onCheckSetup: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.simulation.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }

            Button(onClick = onCheckSetup, modifier = Modifier.fillMaxWidth()) { Text("Check setup") }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Google Play services mock layer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.gmsAvailable) {
                            "Also feeds the simulated fix into Google's fused location (used by Google Maps and most apps). " +
                                "Takes effect on the next start."
                        } else {
                            "Google Play services not available on this device."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = vm.gmsAvailable && (settings?.gmsMockLayer ?: true),
                    enabled = vm.gmsAvailable && !sim.isActive,
                    onCheckedChange = { vm.setGmsLayer(it) },
                )
            }

            HorizontalDivider()
            RoutingSettings(vm)

            HorizontalDivider()
            Text("Last real location", style = MaterialTheme.typography.titleMedium)
            val real = settings?.lastRealLocation
            Text(
                if (real == null) "Not captured yet."
                else String.format(Locale.US, "%.6f, %.6f (±%.0f m)\n", real.lat, real.lon, real.accuracyM) +
                    DateFormat.getDateTimeInstance().format(Date(real.timeMillis)),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "MockRoute ${BuildConfig.VERSION_NAME} (build ${BuildConfig.CI_RUN}, ${BuildConfig.GIT_SHA})\nMap: ${TileStyle.ATTRIBUTION}\n\n$DISCLAIMER_TEXT",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
