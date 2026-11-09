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
