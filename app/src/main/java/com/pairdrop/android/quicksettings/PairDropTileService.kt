package com.pairdrop.android.quicksettings

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pairdrop.android.service.PairDropService

class PairDropTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()
        val nextState = if (PairDropService.isRunning()) {
            PairDropService.stop(this)
            Tile.STATE_INACTIVE
        } else {
            PairDropService.start(this)
            Tile.STATE_ACTIVE
        }
        qsTile?.state = nextState
        qsTile?.updateTile()
    }

    private fun updateState() {
        qsTile?.state = if (PairDropService.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(
                    context,
                    ComponentName(context, PairDropTileService::class.java)
                )
            }
        }
    }
}
