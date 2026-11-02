package com.jarvis.autonomous.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.autonomous.data.AgentRunState
import com.jarvis.autonomous.data.JarvisStateRepository

/**
 * Android Quick Settings Tile ("JARVIS AI") in the status bar pull-down shade.
 * Tapping the tile immediately triggers Jarvis voice listening or stops an active task from any screen.
 */
class JarvisQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "JARVIS AI"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (JarvisStateRepository.agentState.value == AgentRunState.IDLE) {
                    "Tap to Speak"
                } else {
                    "Tap to Stop"
                }
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val isBusy = JarvisStateRepository.agentState.value != AgentRunState.IDLE
        val intent = Intent(this, JarvisForegroundService::class.java).apply {
            action = if (isBusy) {
                JarvisForegroundService.ACTION_STOP_TASK
            } else {
                JarvisForegroundService.ACTION_START_LISTENING
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
