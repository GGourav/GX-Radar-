package com.gxradar.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gxradar.GXRadarApplication
import com.gxradar.R

/**
 * AlbionVpnService — Step 1 stub
 *
 * Starts/stops cleanly with a persistent notification.
 * Step 2 adds:
 *   - TUN interface establishment
 *   - Raw IP packet reader loop
 *   - UDP port 5056 filter (Albion East)
 *   - Photon Protocol16 parser
 *   - Discovery Logger (Plans B & C)
 */
class AlbionVpnService : VpnService() {

    companion object {
        const val ACTION_START  = "com.gxradar.vpn.START"
        const val ACTION_STOP   = "com.gxradar.vpn.STOP"
        const val ALBION_PORT   = 5056  // Same port for all Albion servers
        const val NOTIF_ID      = 1001
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif())
        // TODO Step 2: establish TUN + start capture coroutine
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf() }

    private fun buildNotif() =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar — Capture Active")
            .setContentText("Listening on port $ALBION_PORT (Albion East)")
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true)
            .build()
}
