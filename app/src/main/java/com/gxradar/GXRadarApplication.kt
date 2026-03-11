package com.gxradar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GXRadarApplication : Application() {

    companion object {
        const val CHANNEL_RADAR = "gxradar_radar"
        const val CHANNEL_VPN   = "gxradar_vpn"
        const val CHANNEL_ALERT = "gxradar_alert"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RADAR, "Radar Overlay",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "GX Radar overlay running"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_VPN, "Packet Capture",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "VPN capturing Albion East traffic (port 5056)"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Threat Alerts",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Hostile player nearby"
                }
            )
        }
    }
}
