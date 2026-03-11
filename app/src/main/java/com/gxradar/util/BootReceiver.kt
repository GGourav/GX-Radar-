package com.gxradar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.gxradar.network.AlbionVpnService
import com.gxradar.overlay.RadarOverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("radar_was_running", false)) return

        context.startForegroundService(
            Intent(context, AlbionVpnService::class.java)
                .setAction(AlbionVpnService.ACTION_START)
        )
        context.startForegroundService(
            Intent(context, RadarOverlayService::class.java)
                .setAction(RadarOverlayService.ACTION_START)
        )
    }
}
