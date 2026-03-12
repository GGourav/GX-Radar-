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
import com.gxradar.databinding.ActivityMainBinding
import com.gxradar.network.AlbionVpnService
import com.gxradar.overlay.RadarOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnToggle.setOnClickListener {
            if (isRunning()) stopServices() else checkAndStart()
        }
        binding.btnOverlayPerm.setOnClickListener { openOverlaySettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val running = isRunning()
        binding.btnToggle.text = if (running) "Stop Radar" else "Start Radar"
        binding.tvStatus.text  = if (running) "\u25CF ACTIVE" else "\u25CB STOPPED"
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.status_on else R.color.status_off)
        )
        binding.btnOverlayPerm.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("GX Radar needs to draw over other apps.\n\n1. Tap Open Settings\n2. Enable 'Allow display over other apps'\n3. Return here and tap Start")
                .setPositiveButton("Open Settings") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Skip for now") { _, _ ->
                    // Start VPN only — no HUD until permission granted
                    val vpnIntent = VpnService.prepare(this)
                    if (vpnIntent != null) vpnLauncher.launch(vpnIntent)
                    else startServices()
                }
                .show()
            return
        }
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) vpnLauncher.launch(vpnIntent)
        else startServices()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startServices() {
        try {
            // Start VPN capture
            startForegroundService(
                Intent(this, AlbionVpnService::class.java)
                    .setAction(AlbionVpnService.ACTION_START)
            )
            // Start HUD overlay (shows packet counts)
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(
                    Intent(this, RadarOverlayService::class.java)
                        .setAction(RadarOverlayService.ACTION_START)
                )
            }
            refreshUi()
            toast("GX Radar started")
        } catch (e: Exception) {
            toast("Start failed: ${e.message}")
        }
    }

    private fun stopServices() {
        // Direct stopService() — reliable on all Android versions
        stopService(Intent(this, AlbionVpnService::class.java))
        stopService(Intent(this, RadarOverlayService::class.java))
        refreshUi()
        toast("GX Radar stopped")
    }

    @Suppress("DEPRECATION")
    private fun isRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == AlbionVpnService::class.java.name
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
