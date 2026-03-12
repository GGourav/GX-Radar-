package com.gxradar.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler  = Handler(Looper.getMainLooper())
    private val ticker   = object : Runnable {
        override fun run() { refreshUi(); handler.postDelayed(this, 2000) }
    }

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startServices() else toast("VPN permission denied") }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Safe — storage errors must not crash the Activity
        runCatching { discoveryLogger = DiscoveryLogger(this) }

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
        handler.postDelayed(ticker, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refreshUi() {
        val running = isRunning()
        binding.btnToggle.text = if (running) "Stop Radar" else "Start Radar"
        val ent   = EntityStore.size()
        val logKb = discoveryLogger?.getDebugSizeKb() ?: 0
        val disp  = EventDispatcher.totalDispatched
        val added = EventDispatcher.totalAdded
        val drop  = EventDispatcher.totalDropped
        binding.tvStatus.text = if (running)
            "\u25CF ACTIVE  ENT=$ent  DISP=$disp  ADD=$added  DROP=$drop  LOG=${logKb}KB"
        else
            "\u25CB STOPPED  ENT=$ent  LOG=${logKb}KB"
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.status_on else R.color.status_off)
        )
        binding.btnOverlayPerm.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val hasLog = logKb > 0
        binding.btnShareLog.alpha = if (hasLog) 1f else 0.4f
        binding.btnShareLog.text  =
            if (hasLog) "\uD83D\uDCE4 Share Debug Log (${logKb}KB)" else "Share Debug Log"
    }

    private fun shareDebugLog() {
        val logger = discoveryLogger ?: run { toast("Logger not ready"); return }
        val file = logger.getDebugFile()
        if (!file.exists() || file.length() == 0L) {
            toast("Start radar \u2192 enter zone \u2192 wait 15s \u2192 share")
            return
        }
        try {
            val content = "dispatched=${EventDispatcher.totalDispatched} " +
                "added=${EventDispatcher.totalAdded} dropped=${EventDispatcher.totalDropped}\n\n" +
                file.readText()
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                    putExtra(Intent.EXTRA_SUBJECT, "GX Radar Debug Log")
                }, "Share Log"
            ))
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
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun startServices() {
        runCatching {
            startForegroundService(
                Intent(this, AlbionVpnService::class.java)
                    .setAction(AlbionVpnService.ACTION_START)
            )
        }.onFailure { toast("VPN error: ${it.message}") }

        runCatching {
            startForegroundService(
                Intent(this, RadarOverlayService::class.java)
                    .setAction(RadarOverlayService.ACTION_START)
            )
        }.onFailure { toast("Overlay error: ${it.message}") }

        refreshUi()
        toast("GX Radar started")
    }

    private fun stopServices() {
        // Use stopService() directly — works even if service crashed before
        // registering its broadcast receiver
        runCatching { stopService(Intent(this, AlbionVpnService::class.java)) }
        runCatching { stopService(Intent(this, RadarOverlayService::class.java)) }
        refreshUi()
        toast("GX Radar stopped")
    }

    private fun isRunning(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any {
            it.id == AlbionVpnService.NOTIF_ID || it.id == RadarOverlayService.NOTIF_ID
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
