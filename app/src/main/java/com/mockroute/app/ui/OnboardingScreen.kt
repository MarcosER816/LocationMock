package com.mockroute.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mockroute.app.ui.theme.AppColors
import com.mockroute.app.ui.theme.AppShapes

private class Page(val emoji: String, val color: androidx.compose.ui.graphics.Color, val title: String, val body: String)

private val PAGES = listOf(
    Page("📍", AppColors.Blue, "Pretend to be somewhere else",
        "MockRoute makes your phone report a location you choose: a point, a route along real streets, a walk inside a building or around a neighbourhood."),
    Page("🚶", AppColors.Green, "It moves like a person",
        "Speeds change with traffic and the time of day, walkers wait at crossings, and the GPS signal jitters like the real thing."),
    Page("⏱", AppColors.Purple, "Chain a whole day",
        "Build a trip from steps (route, building, streets, pause). Each step starts where the last one ended. Other apps can detect mock locations: use it for testing and privacy only."),
)

/** Three-screen introduction shown once, before the setup wizard. No animation between pages (app motion rule). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val p = PAGES[page]
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(120.dp).background(p.color.copy(alpha = 0.18f), AppShapes.shapes.extraLarge), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(p.emoji, style = MaterialTheme.typography.displayLarge)
            }
            Text(p.title, style = MaterialTheme.typography.headlineMedium, color = p.color, textAlign = TextAlign.Center)
            Text(p.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAGES.indices.forEach { i ->
                    Box(Modifier.size(width = if (i == page) 22.dp else 8.dp, height = 8.dp).background(if (i == page) p.color else MaterialTheme.colorScheme.outline, AppShapes.shapes.extraSmall))
                }
            }
            // Big one-hand target at the bottom.
            Button(onClick = { if (page < PAGES.lastIndex) page++ else onDone() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (page < PAGES.lastIndex) "Next" else "Get started")
            }
            if (page < PAGES.lastIndex) TextButton(onClick = onDone) { Text("Skip for now") }
        }
    }
}
