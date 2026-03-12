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
import com.gxradar.R
import com.gxradar.data.model.EntityStore
import com.gxradar.databinding.ActivityMainBinding
import com.gxradar.network.AlbionVpnService
import com.gxradar.network.DiscoveryLogger
import com.gxradar.network.EventDispatcher
import com.gxradar.overlay.RadarOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var discoveryLogger: DiscoveryLogger? = null

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startServices()
        else toast("VPN permission denied")
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Safe init — wrapped so storage errors don't crash the app
        try { discoveryLogger = DiscoveryLogger(this) } catch (e: Exception) { }

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

        val ent     = EntityStore.size()
        val logKb   = discoveryLogger?.getDebugSizeKb() ?: 0
        val disp    = EventDispatcher.totalDispatched
        val added   = EventDispatcher.totalAdded

        binding.tvStatus.text = if (running) {
            "● ACTIVE  ENT=$ent  DISP=$disp  ADD=$added  LOG=${logKb}KB"
        } else {
            "○ STOPPED  last: ENT=$ent  LOG=${logKb}KB"
        }
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.status_on else R.color.status_off)
        )
        binding.btnOverlayPerm.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE

        val hasLog = (discoveryLogger?.getDebugSizeKb() ?: 0) > 0
        binding.btnShareLog.alpha = if (hasLog) 1.0f else 0.4f
        binding.btnShareLog.text  = if (hasLog)
            "📤 Share Debug Log (${logKb}KB)" else "📤 Share Debug Log"
    }

    private fun shareDebugLog() {
        val logger = discoveryLogger
        if (logger == null) { toast("Logger not ready"); return }
        val file = logger.getDebugFile()
        if (!file.exists() || file.length() == 0L) {
            toast("No log yet — start radar, enter a zone, wait 15 sec")
            return
        }
        try {
            val summary = "dispatched=${EventDispatcher.totalDispatched} " +
                "added=${EventDispatcher.totalAdded} " +
                "dropped=${EventDispatcher.totalDropped}\n\n"
            val content = summary + file.readText()

            // Share as plain text directly — no FileProvider needed
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, "GX Radar Debug Log")
            }
            startActivity(Intent.createChooser(intent, "Share Debug Log"))
        } catch (e: Exception) {
            toast("Share failed: ${e.message}")
        }
    }

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("GX Radar needs to draw over other apps.\n\n1. Tap Open Settings\n2. Enable Allow display over other apps\n3. Return here and tap Start")
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
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
        )
    }

    private fun startServices() {
        startForegroundService(
            Intent(this, AlbionVpnService::class.java)
                .setAction(AlbionVpnService.ACTION_START)
        )
        startForegroundService(
            Intent(this, RadarOverlayService::class.java)
                .setAction(RadarOverlayService.ACTION_START)
        )
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

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
