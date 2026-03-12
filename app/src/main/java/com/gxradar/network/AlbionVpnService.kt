package com.gxradar.network

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gxradar.GXRadarApplication
import com.gxradar.R
import com.gxradar.data.model.EntityStore
import com.gxradar.network.photon.PhotonParser
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AlbionVpnService — crash-safe rewrite.
 *
 * Uses simple blocking DatagramSocket instead of NIO Selector.
 * NIO Selector caused crashes on certain Android 12/13 ROMs.
 * This version is simpler and more compatible.
 *
 * KEY: addAllowedApplication("com.albiononline")
 *   Only Albion traffic enters TUN — all other apps bypass completely.
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG        = "AlbionVpnService"
        const val ACTION_START       = "com.gxradar.vpn.START"
        const val ACTION_STOP        = "com.gxradar.vpn.STOP"
        const val ALBION_PACKAGE     = "com.albiononline"
        const val ALBION_PORT        = 5056
        const val NOTIF_ID           = 1001
        private const val MTU        = 32767
        private const val TUN_IP     = "10.8.0.2"
        private const val TUN_PREFIX = 32

        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)
    }

    private var tunPfd:            ParcelFileDescriptor? = null
    private var tunOut:            FileOutputStream?     = null
    private val scope              = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dispatcher:      EventDispatcher
    private lateinit var discoveryLogger: DiscoveryLogger

    // Simple UDP relay map: srcPort -> outbound socket
    private val udpSockets = ConcurrentHashMap<Int, DatagramSocket>()

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_STOP) stopSelf()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            discoveryLogger = DiscoveryLogger(this)
            dispatcher      = EventDispatcher(discoveryLogger)
            val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            startForeground(NOTIF_ID, buildNotif("Starting…"))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
        }
        scope.launch {
            try { runCapture() }
            catch (e: Exception) { Log.e(TAG, "runCapture crashed: ${e.message}") }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        udpSockets.values.forEach { runCatching { it.close() } }
        udpSockets.clear()
        runCatching { tunPfd?.close() }
        tunPfd = null
        EntityStore.clear()
        packetCount.set(0); albionCount.set(0)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ─── TUN setup ────────────────────────────────────────────────────────────

    private suspend fun runCapture() = withContext(Dispatchers.IO) {
        val pfd = try {
            Builder()
                .setSession("GX Radar")
                .addAddress(TUN_IP, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(MTU)
                .setBlocking(true)
                .addAllowedApplication(ALBION_PACKAGE)  // ★ CRITICAL: only Albion goes through TUN
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "TUN establish failed: ${e.message}")
            stopSelf(); return@withContext
        }

        if (pfd == null) {
            Log.e(TAG, "TUN establish returned null")
            stopSelf(); return@withContext
        }

        tunPfd = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        notify("Capturing Albion port $ALBION_PORT")
        Log.i(TAG, "TUN established — listening")

        runTunReadLoop(pfd)
    }

    // ─── TUN read loop ────────────────────────────────────────────────────────

    private suspend fun runTunReadLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf   = ByteArray(MTU)
            Log.i(TAG, "TUN read loop started")
            try {
                while (isActive) {
                    val len = try { input.read(buf) } catch (e: Exception) { break }
                    if (len < 20) continue
                    packetCount.incrementAndGet()

                    // IPv4 only
                    if ((buf[0].toInt() and 0xF0) != 0x40) continue
                    val ihl   = (buf[0].toInt() and 0x0F) * 4
                    val proto = buf[9].toInt() and 0xFF
                    if (proto != 17) continue  // UDP only
                    if (len < ihl + 8) continue

                    val srcPort = buf.u16(ihl)
                    val dstPort = buf.u16(ihl + 2)
                    val payOff  = ihl + 8
                    val payLen  = len - payOff
                    if (payLen <= 0) continue

                    if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
                        albionCount.incrementAndGet()
                        // Parse Photon on the main capture thread (fast enough for UDP)
                        try {
                            val payload = buf.copyOfRange(payOff, payOff + payLen)
                            PhotonParser.parse(payload).forEach { dispatcher.dispatch(it) }
                        } catch (e: Exception) {
                            Log.v(TAG, "photon parse: ${e.message}")
                        }
                        updateNotif()
                    }

                    // Forward packet via protected DatagramSocket
                    try {
                        val dstIp  = InetAddress.getByAddress(buf.copyOfRange(16, 20))
                        val socket = udpSockets.getOrPut(srcPort) {
                            DatagramSocket().also {
                                protect(it)  // protect BEFORE any send
                                it.soTimeout = 0
                            }
                        }
                        socket.send(DatagramPacket(buf, payOff, payLen, dstIp, dstPort))
                    } catch (e: Exception) {
                        udpSockets.remove(srcPort)?.runCatching { close() }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "TUN loop cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TUN loop error: ${e.message}")
            }
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun ByteArray.u16(off: Int) =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun notify(text: String) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotif(text))
        }
    }

    private fun updateNotif() {
        notify("PKT ${packetCount.get()}  ALB ${albionCount.get()}  ENT ${EntityStore.size()}")
    }
}
