package com.mockroute.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mockroute.app.ui.AppRoot
import com.mockroute.app.ui.MainViewModel
import com.mockroute.app.ui.theme.MockRouteTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: light system-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MockRouteTheme { AppRoot(vm) }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Launcher shortcut / Quick Settings tile: "start my last trip" (the app is visible, so this is a UI action). */
    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_PLAY_LAST) vm.playLastTrip()
    }

    companion object {
        const val ACTION_PLAY_LAST = "com.mockroute.app.PLAY_LAST"
    }
}
