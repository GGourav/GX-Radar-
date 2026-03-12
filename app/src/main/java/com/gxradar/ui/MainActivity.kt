package com.gxradar.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.gxradar.R
import com.gxradar.databinding.ActivityMainBinding
import com.gxradar.network.AlbionVpnService
import com.gxradar.network.DiscoveryLogger
import com.gxradar.network.EventDispatcher
import com.gxradar.overlay.RadarOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var discoveryLogger: DiscoveryLogger

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startServices()
        else toast("VPN permission denied")
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* non-critical */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        discoveryLogger = DiscoveryLogger(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnToggle.setOnClickListener {
            if (isRunning()) stopServices() else checkAndStart()
        }
        binding.btnOverlayPerm.setOnClickListener { openOverlaySettings() }
        binding.btnShareLog.setOnClickListener { shareDebugLog() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val running = isRunning()
        binding.btnToggle.text = if (running) "Stop Radar" else "Start Radar"
        binding.tvStatus.text  = if (running) {
            "● ACTIVE · ENT ${com.gxradar.data.model.EntityStore.size()} · LOG ${discoveryLogger.getDebugSizeKb()}KB"
        } else {
            "○ STOPPED"
        }
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.status_on else R.color.status_off)
        )
        binding.btnOverlayPerm.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE

        // Show share button only when there's something to share
        val logSize = discoveryLogger.getDebugSizeKb()
        binding.btnShareLog.text = if (logSize > 0) "📤 Share Debug Log (${logSize}KB)" else "📤 Share Debug Log"
        binding.btnShareLog.alpha = if (logSize > 0) 1.0f else 0.5f
    }

    // ── Share debug log via Android share sheet ───────────────────────────────

    private fun shareDebugLog() {
        val file = discoveryLogger.getDebugFile()
        if (!file.exists() || file.length() == 0L) {
            toast("No log yet — start radar, enter a zone, wait 15 sec, then share")
            return
        }

        // Summary line at top
        val summary = "dispatched=${EventDispatcher.totalDispatched} " +
            "added=${EventDispatcher.totalAdded} dropped=${EventDispatcher.totalDropped}\n"
        try {
            val content = summary + file.readText()
            // Write combined to a temp share file
            val shareFile = java.io.File(cacheDir, "gxradar_debug_share.txt")
            shareFile.writeText(content)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                shareFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GX Radar Debug Log")
                putExtra(Intent.EXTRA_TEXT, "GX Radar debug log — ENT=${com.gxradar.data.model.EntityStore.size()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Debug Log"))
        } catch (e: Exception) {
            toast("Share failed: ${e.message}")
        }
    }

    // ── Existing methods ──────────────────────────────────────────────────────

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("GX Radar needs to draw over other apps.\n\n1. Tap Open Settings\n2. Enable 'Allow display over other apps'\n3. Return here and tap Start")
                .setPositiveButton("Open Settings") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) vpnLauncher.launch(vpnIntent)
        else startServices()
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startServices() {
        startForegroundService(Intent(this, AlbionVpnService::class.java).setAction(AlbionVpnService.ACTION_START))
        startForegroundService(Intent(this, RadarOverlayService::class.java).setAction(RadarOverlayService.ACTION_START))
        refreshUi()
        toast("GX Radar started")
    }

    private fun stopServices() {
        sendBroadcast(Intent(AlbionVpnService.ACTION_STOP).setPackage(packageName))
        sendBroadcast(Intent(RadarOverlayService.ACTION_STOP).setPackage(packageName))
        refreshUi()
        toast("GX Radar stopped")
    }

    @Suppress("DEPRECATION")
    private fun isRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == RadarOverlayService::class.java.name
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
