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
import androidx.appcompat.app.AppCompatActivity
import com.gxradar.R
import com.gxradar.databinding.ActivityMainBinding
import com.gxradar.network.AlbionVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        else toast("VPN permission denied — radar cannot capture packets")
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
            if (isVpnRunning()) stopServices() else checkAndStart()
        }
        binding.btnOverlayPerm.setOnClickListener { openOverlaySettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val running = isVpnRunning()
        binding.btnToggle.text = if (running) "Stop Radar" else "Start Radar"
        binding.tvStatus.text  = if (running) "\u25CF ACTIVE \u2014 capturing port 5056" else "\u25CB STOPPED"
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.status_on else R.color.status_off)
        )
        binding.btnOverlayPerm.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun checkAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) vpnLauncher.launch(vpnIntent)
        else startVpnService()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startVpnService() {
        try {
            startForegroundService(
                Intent(this, AlbionVpnService::class.java)
                    .setAction(AlbionVpnService.ACTION_START)
            )
            refreshUi()
            toast("GX Radar started \u2014 listening on port 5056")
        } catch (e: Exception) {
            toast("Failed to start: ${e.message}")
        }
    }

    private fun stopServices() {
        sendBroadcast(
            Intent(AlbionVpnService.ACTION_STOP).setPackage(packageName)
        )
        refreshUi()
        toast("GX Radar stopped")
    }

    @Suppress("DEPRECATION")
    private fun isVpnRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == AlbionVpnService::class.java.name
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
