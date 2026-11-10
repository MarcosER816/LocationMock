package com.mockroute.app.mock

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mockroute.app.MainActivity
import com.mockroute.app.R
import com.mockroute.app.sim.SimulationRepository

/**
 * Quick Settings tile. While simulating: tap = Stop. Otherwise: tap opens MockRoute and starts the last trip
 * (the service is only ever started from the visible app, as the mock-location rules require).
 */
class MockTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (SimulationRepository.state.value.isActive) {
            MockLocationService.stop(this)
        } else {
            val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PLAY_LAST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val active = SimulationRepository.state.value.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(if (active) R.string.tile_stop else R.string.tile_start)
        tile.updateTile()
    }
}
