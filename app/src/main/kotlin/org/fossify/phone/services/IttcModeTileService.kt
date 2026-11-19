package org.fossify.phone.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.helpers.IttcMode

/**
 * 🎓 ITTC 3.12: the Quick Settings tile "ITTC mode": on (active) in ITTC mode, off in Personal mode;
 * a tap switches (the admin's limits apply: a toast says why not). Not signed in to ITTC: unavailable.
 */
class IttcModeTileService : TileService() {
    private val listener: () -> Unit = { show() }

    override fun onStartListening() {
        super.onStartListening()
        IttcMode.addListener(listener)
        show()
        // (the sign-in is read off the main thread: the tile follows in a moment)
        val app = applicationContext
        ensureBackgroundThread {
            val now = try {
                IttcDirect.isSignedIn(app)
            } catch (_: Throwable) {
                false
            }
            if (now != signedIn) {
                signedIn = now
                android.os.Handler(android.os.Looper.getMainLooper()).post { show() }
            }
        }
    }

    override fun onStopListening() {
        IttcMode.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!signedIn) {
            Toast.makeText(applicationContext, R.string.ittc_mode_tile_signed_out, Toast.LENGTH_SHORT).show()
            return
        }
        val personal = !IttcMode.isPersonal(this)
        if (IttcMode.request(this, personal, "tile")) {
            Toast.makeText(
                applicationContext,
                if (personal) R.string.ittc_mode_personal_toast else R.string.ittc_mode_ittc_toast,
                Toast.LENGTH_SHORT,
            ).show()
        }
        show()
    }

    private companion object {
        /** Signed in to ITTC, as last read (kept for the next time the panel opens) */
        @Volatile
        var signedIn = false
    }

    private fun show() {
        val tile = qsTile ?: return
        try {
            val personal = IttcMode.isPersonal(this)
            tile.label = getString(R.string.ittc_mode_tile)
            tile.state = when {
                !signedIn -> Tile.STATE_UNAVAILABLE
                personal -> Tile.STATE_INACTIVE
                else -> Tile.STATE_ACTIVE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(if (personal) R.string.ittc_mode_tile_personal else R.string.ittc_mode_tile_ittc)
            }
            tile.updateTile()
        } catch (_: Throwable) {
        }
    }
}
