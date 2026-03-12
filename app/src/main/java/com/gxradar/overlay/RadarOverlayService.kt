package com.gxradar.overlay

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gxradar.GXRadarApplication
import com.gxradar.R
import com.gxradar.ui.MainActivity

/**
 * RadarOverlayService — crash-safe version.
 * Extends plain Service (not LifecycleService) to avoid lifecycle-ktx issues.
 * Step 4 will add the SurfaceView overlay rendering.
 */
class RadarOverlayService : Service() {

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotif())
        } catch (e: Exception) {
            // If foreground fails (rare on some ROMs), keep running silently
        }
        // TODO Step 4: attach SurfaceView radar overlay via WindowManager
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
            .setContentText("Tap to open")
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
}
