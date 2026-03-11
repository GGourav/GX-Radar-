package com.gxradar.overlay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.gxradar.GXRadarApplication
import com.gxradar.R
import com.gxradar.ui.MainActivity

/**
 * RadarOverlayService — Step 1 stub
 *
 * Starts/stops cleanly with a persistent notification.
 * Step 4 adds:
 *   - SYSTEM_ALERT_WINDOW SurfaceView via WindowManager
 *   - Hardware-accelerated Canvas render thread
 *   - Floating filter panel UI
 *   - Colored dot & enchant ring drawing
 */
class RadarOverlayService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.gxradar.overlay.START"
        const val ACTION_STOP  = "com.gxradar.overlay.STOP"
        const val NOTIF_ID     = 1002
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
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif())
        // TODO Step 4: attach SurfaceView overlay via WindowManager
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stopReceiver) }
        // TODO Step 4: detach SurfaceView
        super.onDestroy()
    }

    private fun buildNotif() =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_RADAR)
            .setContentTitle("GX Radar — Overlay Active")
            .setContentText("Tap to open settings")
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
}
