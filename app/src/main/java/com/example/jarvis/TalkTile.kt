package com.example.jarvis

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class TalkTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Friday"; updateTile() }
    }

    override fun onClick() {
        if (AssistantService.instance == null) {
            Toast.makeText(this, "Open Friday and tap Start assistant first", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, AssistantService::class.java).setAction("LISTEN"))
    }
}
