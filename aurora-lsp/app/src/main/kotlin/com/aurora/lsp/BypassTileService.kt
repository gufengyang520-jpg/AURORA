package com.aurora.lsp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.aurora.audio.lsp.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One-tap A/B toggle in Quick Settings: flips {@code aurora_bypass} between 0 and 1 and reflects
 * it straight back on the tile, so the "原声 vs 处理" comparison is a single swipe-and-tap away.
 *
 * <p>The key is a Settings.Global value that needs root to write, so the flip goes through
 * {@code su} on a background thread; the tile UI is updated as soon as the write is issued, not
 * after a Settings re-read (which the engine would not observe here anyway - there is no
 * ContentObserver on the tile process).
 */
class BypassTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onClick() {
        super.onClick()
        val qsTile = qsTile ?: return
        qsTile.state = Tile.STATE_UNAVAILABLE
        qsTile.updateTile()
        val current = readBypass()
        val next = !current
        thread(name = "aurora-bypass-tile") {
            writeBypass(next)
            main.post { reflect(next) }
        }
    }

    /** Reflects the state the user just asked for; a failed write leaves it unverified. */
    private fun reflect(value: Boolean) {
        val qsTile = qsTile ?: return
        qsTile.state = if (value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.label = getString(R.string.tile_bypass, if (value) "开" else "关")
        qsTile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        val value = readBypass()
        val qsTile = qsTile ?: return
        qsTile.state = if (value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.label = getString(R.string.tile_bypass, if (value) "开" else "关")
        qsTile.updateTile()
    }

    private fun readBypass(): Boolean = try {
        Settings.Global.getInt(contentResolver, AuroraEngine.K_BYPASS, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /** Mirrors AuroraState.runRoot: su -c, 15s cap, no result parsing needed for a settings put. */
    private fun writeBypass(value: Boolean) {
        val command = "settings put global " + AuroraEngine.K_BYPASS + " " + (if (value) 1 else 0)
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val running = process
            thread(name = "aurora-bypass-drain") {
                try {
                    BufferedReader(InputStreamReader(running.inputStream)).use { it.readLines() }
                } catch (_: Throwable) {
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                main.post {
                    Toast.makeText(this, R.string.tile_bypass_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) {
            process?.destroyForcibly()
            main.post {
                Toast.makeText(this, R.string.tile_bypass_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
