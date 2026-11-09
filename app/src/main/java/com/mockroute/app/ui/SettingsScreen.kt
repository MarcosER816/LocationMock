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
        label = "OpenRouteService API key",
        value = s.orsApiKey,
        builtIn = BuildConfig.ORS_API_KEY.isNotBlank(),
        onSave = vm::setOrsKey,
    )
    KeyField(
        label = "GraphHopper API key",
        value = s.graphHopperApiKey,
        builtIn = BuildConfig.GRAPHHOPPER_API_KEY.isNotBlank(),
        onSave = vm::setGraphHopperKey,
    )
    Text(
        "OpenRouteService (HeiGIT, api.heigit.org): free key at openrouteservice.org — ~2,000 directions/day, " +
            "40/min. GraphHopper free: 500 credits/day, non-commercial. Without a key MockRoute draws straight lines. " +
            "Routes are cached on the phone for 30 days.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = vm::clearRouteCache) { Text("Clear route cache (${vm.routeCache.entryCount()})") }
}

@Composable
private fun KeyField(label: String, value: String, builtIn: Boolean, onSave: (String) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        supportingText = {
            Text(
                when {
                    value.isNotBlank() -> "Using the key saved on this phone"
                    builtIn -> "Empty: using the key built in from GitHub secrets"
                    else -> "No key: straight-line fallback"
                },
            )
        },
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (text != value) {
        Button(onClick = { onSave(text) }) { Text("Save key") }
    }
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
                "MockRoute ${BuildConfig.VERSION_NAME}\nMap: ${TileStyle.ATTRIBUTION}\n\n$DISCLAIMER_TEXT",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
