package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GeneralSettings(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    @Composable
    fun choice(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, label) ->
                androidx.compose.material3.FilterChip(selected = current == id, onClick = { onPick(id) }, label = { Text(label) })
            }
        }
    }
    choice("Language", listOf("system" to "System", "en" to "English", "es" to "Español"), s.language, vm::setLanguage)
    choice("Units", listOf("metric" to "Metric (km/h, m)", "imperial" to "Imperial (mph, ft)"), if (s.imperialUnits) "imperial" else "metric") { vm.setImperial(it == "imperial") }
    choice("Map style", listOf("dark" to "Dark", "light" to "Light"), s.mapStyle, vm::setMapStyle)
    // Shows whether the extra building layer loads (tell the developer what it says if buildings are missing).
    val overture by com.mockroute.app.map.OvertureBuildings.status.collectAsStateWithLifecycle()
    androidx.compose.material3.Text(overture, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Background reliability: a 10-minute throttling test, and the Xiaomi / HyperOS checklist. */
@Composable
private fun BackgroundSection(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val result by vm.backgroundTest.collectAsStateWithLifecycle()
    val stats by vm.tickStats.collectAsStateWithLifecycle()
    val sim by vm.simulation.collectAsStateWithLifecycle()
    Text("Background test", style = MaterialTheme.typography.titleMedium)
    Text(
        "Checks that the phone lets MockRoute keep running with the screen off. It simulates standing at your real " +
            "location for 10 minutes and measures the gaps between updates (there should be one every second).",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = vm::runBackgroundTest, enabled = !sim.isActive) { Text("Run 10-minute test") }
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("PASS")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary) }
    if (stats.ticks > 0) {
        Text(
            String.format(Locale.US, "Last run: %d updates, longest gap %.1f s, %d late", stats.ticks, stats.maxGapMs / 1000.0, stats.lateTicks),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (com.mockroute.app.setup.SetupIntents.isXiaomi()) {
        Text("Xiaomi / HyperOS helper", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. Autostart: ON.\n2. Battery saver (“Power”) → No restrictions.\n3. Recents → lock MockRoute (padlock).\n" +
                "4. Don't use Ultra battery saver while simulating.\n5. Check these again after every HyperOS update: updates can reset them.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { com.mockroute.app.setup.SetupIntents.openAutostart(context) }) { Text("Open Autostart") }
            OutlinedButton(onClick = { com.mockroute.app.setup.SetupIntents.openAppDetails(context) }) { Text("Open app info") }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NoiseSettingsSection(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val n = settings?.noise ?: return
    Text("GPS noise", style = MaterialTheme.typography.titleMedium)
    Text(
        "Makes the simulated location jitter like a real phone's GPS instead of following a perfect line: a slowly " +
            "wandering error, a changing accuracy circle, occasional jumps near buildings, a few missed fixes. " +
            "Applies from the next Play.",
        style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.mockroute.core.noise.NoisePreset.entries.forEach { p ->
            androidx.compose.material3.FilterChip(selected = n.preset == p, onClick = { vm.setNoise { it.copy(preset = p) } }, label = { Text(p.label) })
        }
    }
    if (n.enabled) {
        com.mockroute.core.noise.Environment.entries.forEach { env ->
            val scale = when (env) {
                com.mockroute.core.noise.Environment.OpenSky -> n.openSkyScale
                com.mockroute.core.noise.Environment.Urban -> n.urbanScale
                com.mockroute.core.noise.Environment.Indoor -> n.indoorScale
            }
            val p = n.params(env)
            Text(
                String.format(Locale.US, "%s: error ×%.2f → typical accuracy ≈ %.0f m", env.label, scale, 1.51 * p.sigmaM),
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.Slider(
                value = scale.toFloat(),
                onValueChange = { v ->
                    val x = (Math.round(v * 20) / 20.0)
                    vm.setNoise {
                        when (env) {
                            com.mockroute.core.noise.Environment.OpenSky -> it.copy(openSkyScale = x)
                            com.mockroute.core.noise.Environment.Urban -> it.copy(urbanScale = x)
                            com.mockroute.core.noise.Environment.Indoor -> it.copy(indoorScale = x)
                        }
                    }
                },
                valueRange = 0.25f..3f,
            )
        }
        var seedText by rememberSaveable { mutableStateOf(if (n.seed == 0L) "" else n.seed.toString()) }
        OutlinedTextField(
            value = seedText,
            onValueChange = { t ->
                seedText = t.filter { it.isDigit() }.take(15)
                vm.setNoise { it.copy(seed = seedText.toLongOrNull() ?: 0L) }
            },
            label = { Text("Seed (empty = different every run)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Debug overlay", style = MaterialTheme.typography.bodyMedium)
                Text("Show the true path (black), the injected points (purple; red = jump) and the accuracy circle (blue) on the map", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = n.debugOverlay, onCheckedChange = { on -> vm.setNoise { it.copy(debugOverlay = on) } })
        }
    }
}

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
            GeneralSettings(vm)

            HorizontalDivider()
            BackgroundSection(vm)

            HorizontalDivider()
            NoiseSettingsSection(vm)

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
