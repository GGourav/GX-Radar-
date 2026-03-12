package com.gxradar.overlay

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gxradar.GXRadarApplication
import com.gxradar.R
import com.gxradar.ui.MainActivity

/**
 * RadarOverlayService — plain Service, no foregroundServiceType.
 * Step 4 adds SurfaceView overlay via WindowManager.
 */
class RadarOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.gxradar.overlay.START"
        const val ACTION_STOP  = "com.gxradar.overlay.STOP"
        const val NOTIF_ID     = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotif())
        // TODO Step 4: attach SurfaceView overlay
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // TODO Step 4: detach SurfaceView
        super.onDestroy()
    }

    private fun buildNotif() =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_RADAR)
            .setContentTitle("GX Radar — Active")
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
