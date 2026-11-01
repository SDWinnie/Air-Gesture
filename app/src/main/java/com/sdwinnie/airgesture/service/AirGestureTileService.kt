package com.sdwinnie.airgesture.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.ui.MainActivity

/**
 * Android 14–16+ Quick Settings Tile allowing 1-tap touchless activation/deactivation
 * straight from the system notification shade.
 */
class AirGestureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileVisualState()
    }

    override fun onClick() {
        super.onClick()
        val running = AirGestureRuntimeBus.telemetry.value.serviceRunning
        if (running) {
            AirGestureForegroundService.stopService(this)
            updateTile(active = false)
            return
        }

        val prefs = PreferenceStore(this)
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (prefs.isProminentDisclosureAccepted && hasCamera) {
            AirGestureForegroundService.startService(this)
            updateTile(active = true)
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val wrapper = PendingIntentActivityWrapper(
                this,
                205,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                false
            )
            TileServiceCompat.startActivityAndCollapse(this, wrapper)
        }
    }

    private fun syncTileVisualState() {
        updateTile(active = AirGestureRuntimeBus.telemetry.value.serviceRunning)
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "On-Device Active" else "Off"
        }
        tile.updateTile()
    }
}
