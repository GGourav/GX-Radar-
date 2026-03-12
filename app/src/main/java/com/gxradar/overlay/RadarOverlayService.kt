package com.gxradar.overlay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.gxradar.GXRadarApplication
import com.gxradar.R
import com.gxradar.data.model.EntityStore
import com.gxradar.network.AlbionVpnService
import com.gxradar.ui.MainActivity

/**
 * RadarOverlayService — Step 2 debug HUD
 *
 * Shows a small floating counter:
 *   PKT  123   — total UDP packets seen by VPN
 *   ALB  45    — packets on port 5056 (Albion)
 *   ENT  12    — entities currently in EntityStore
 *
 * Step 4 replaces this with the full SurfaceView radar canvas.
 */
class RadarOverlayService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.gxradar.overlay.START"
        const val ACTION_STOP  = "com.gxradar.overlay.STOP"
        const val NOTIF_ID     = 1002
    }

    private var windowManager: WindowManager? = null
    private var hudView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L   // refresh HUD every second

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, updateInterval)
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopSelf()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif())
        if (Settings.canDrawOverlays(this)) attachHud()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        detachHud()
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    private fun attachHud() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val tv = TextView(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setTextColor(Color.parseColor("#2979FF"))
            textSize   = 11f
            typeface   = Typeface.MONOSPACE
            setPadding(12, 8, 12, 8)
            text = "GX RADAR\nPKT  --\nALB  --\nENT  --"
        }
        hudView = tv

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 48
        }

        try {
            wm.addView(tv, params)
            handler.post(updateRunnable)
        } catch (e: Exception) {
            // Overlay permission revoked mid-session
        }
    }

    private fun detachHud() {
        try {
            hudView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* already removed */ }
        hudView = null
    }

    private fun updateHud() {
        val pkt = AlbionVpnService.packetCount.get()
        val alb = AlbionVpnService.albionCount.get()
        val ent = EntityStore.size()
        hudView?.text = "GX RADAR\nPKT  $pkt\nALB  $alb\nENT  $ent"
    }

    // ── Notification ──────────────────────────────────────────────────────────

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
