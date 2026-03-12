package com.gxradar.network

import android.app.NotificationManager
import android.net.VpnService
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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import android.content.Intent

/**
 * AlbionVpnService — complete rewrite.
 *
 * KEY FACTS from APK reverse engineering:
 *  1. addAllowedApplication("com.albiononline") — ONLY Albion enters TUN
 *     → All other apps bypass VPN completely → internet works normally
 *  2. Stop via stopService() from Activity — no broadcast needed
 *  3. UDP proxy: read TUN → parse Photon → forward to server → write response back to TUN
 *
 * Why previous versions crashed on Start:
 *  - foregroundServiceType="specialUse" in manifest → SecurityException on most devices
 *  - lateinit dispatcher accessed before init → UninitializedPropertyAccessException
 *  - Both fixed here.
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG       = "AlbionVPN"
        const val ACTION_START      = "com.gxradar.vpn.START"
        const val ALBION_PACKAGE    = "com.albiononline"
        const val ALBION_PORT       = 5056
        const val NOTIF_ID          = 1001
        private const val MTU       = 32767
        private const val TUN_IP    = "10.8.0.2"
        private const val TUN_MASK  = 32

        val packetCount  = AtomicLong(0)
        val albionCount  = AtomicLong(0)
    }

    // Nullable — safe even if init fails
    private var dispatcher:      EventDispatcher?      = null
    private var discoveryLogger: DiscoveryLogger?      = null
    private var tunPfd:          ParcelFileDescriptor? = null

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // sessionId -> protected outbound socket
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // Safe init — if storage fails, logger stays null, dispatcher uses null logger
        runCatching { discoveryLogger = DiscoveryLogger(this) }
        runCatching { dispatcher      = EventDispatcher(discoveryLogger!!) }
            .onFailure {
                // Make dispatcher work even without logger
                runCatching { dispatcher = object : EventDispatcher(DiscoveryLogger(this)) {} }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("Starting…"))
        scope.launch {
            try { runCapture() }
            catch (e: CancellationException) { /* normal stop */ }
            catch (e: Exception) { Log.e(TAG, "capture error: ${e.message}") }
        }
        return START_NOT_STICKY  // Don't auto-restart — prevents flooding
    }

    override fun onDestroy() {
        scope.cancel()
        udpSockets.values.forEach { runCatching { it.close() } }
        udpSockets.clear()
        runCatching { tunPfd?.close() }
        EntityStore.clear()
        packetCount.set(0)
        albionCount.set(0)
        Log.i(TAG, "VPN destroyed")
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ── TUN Setup ─────────────────────────────────────────────────────────────

    private suspend fun runCapture() = withContext(Dispatchers.IO) {
        val pfd = try {
            Builder()
                .setSession("GX Radar")
                .addAddress(TUN_IP, TUN_MASK)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(MTU)
                .setBlocking(true)
                .addAllowedApplication(ALBION_PACKAGE)  // ★ ONLY Albion enters TUN
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "TUN establish failed: ${e.message}")
            stopSelf()
            return@withContext
        } ?: run {
            Log.e(TAG, "TUN establish returned null")
            stopSelf()
            return@withContext
        }

        tunPfd = pfd
        notify("Capturing port $ALBION_PORT")
        Log.i(TAG, "TUN established")

        val tunIn  = FileInputStream(pfd.fileDescriptor)
        val tunOut = FileOutputStream(pfd.fileDescriptor)
        val inBuf  = ByteArray(MTU)

        while (isActive) {
            val len = try { tunIn.read(inBuf) } catch (e: Exception) { break }
            if (len < 20) continue
            packetCount.incrementAndGet()

            // IPv4 only
            if ((inBuf[0].toInt() and 0xF0) != 0x40) continue
            val ihl      = (inBuf[0].toInt() and 0x0F) * 4
            val protocol = inBuf[9].toInt() and 0xFF
            if (protocol != 17 || len < ihl + 8) continue  // UDP only

            val dstPort = inBuf.u16(ihl + 2)
            val srcPort = inBuf.u16(ihl)
            val payOff  = ihl + 8
            val payLen  = len - payOff
            if (payLen <= 0) continue

            // ── Parse Photon if Albion game port ──────────────────────────────
            if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
                albionCount.incrementAndGet()
                try {
                    val payload = inBuf.copyOfRange(payOff, payOff + payLen)
                    val messages = PhotonParser.parse(payload)
                    messages.forEach { dispatcher?.dispatch(it) }
                    if (messages.isNotEmpty() && albionCount.get() % 50 == 0L) updateNotif()
                } catch (e: Exception) {
                    Log.v(TAG, "photon: ${e.message}")
                }
            }

            // ── Forward UDP via protected socket ──────────────────────────────
            // Extract destination IP and forward
            try {
                val dstIpBytes = inBuf.copyOfRange(16, 20)
                val dstIp      = InetAddress.getByAddress(dstIpBytes)
                val key        = "$srcPort:${dstIp.hostAddress}:$dstPort"

                val sock = udpSockets.getOrPut(key) {
                    DatagramSocket().also { s ->
                        if (!protect(s)) {
                            s.close()
                            Log.w(TAG, "protect() failed")
                        }
                    }
                }

                // Send outbound
                sock.send(DatagramPacket(inBuf, payOff, payLen, dstIp, dstPort))

                // Receive response (non-blocking check)
                sock.soTimeout = 1
                val respBuf = ByteArray(MTU)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                try {
                    sock.receive(respPkt)
                    // Write response back into TUN as inbound IP packet
                    val response = buildIpPacket(
                        srcIp   = dstIpBytes,
                        dstIp   = ByteArray(4).also {
                            ByteBuffer.wrap(inBuf).getInt(12).let { ipInt ->
                                it[0] = (ipInt shr 24).toByte()
                                it[1] = (ipInt shr 16).toByte()
                                it[2] = (ipInt shr 8).toByte()
                                it[3] = ipInt.toByte()
                            }
                        },
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = respBuf,
                        payLen  = respPkt.length
                    )
                    if (response != null) tunOut.write(response)
                } catch (e: java.net.SocketTimeoutException) {
                    // No response yet — normal for UDP
                }
            } catch (e: Exception) {
                // Remove broken socket, will recreate next time
                udpSockets.remove(inBuf.u16(ihl).toString())?.runCatching { close() }
            }
        }
    }

    // ── Build inbound IP+UDP packet to write back into TUN ────────────────────

    private fun buildIpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payLen: Int
    ): ByteArray? {
        if (payLen <= 0) return null
        val udpLen   = 8 + payLen
        val totalLen = 20 + udpLen
        val pkt      = ByteArray(totalLen)
        val bb       = ByteBuffer.wrap(pkt)

        // IP header
        bb.put(0x45.toByte())           // Version=4, IHL=5
        bb.put(0)
        bb.putShort(totalLen.toShort()) // Total length
        bb.putShort(0)                  // ID
        bb.putShort(0x4000.toShort())   // Don't fragment
        bb.put(64)                      // TTL
        bb.put(17)                      // Protocol=UDP
        bb.putShort(0)                  // Checksum (skip)
        bb.put(srcIp)                   // Source IP
        bb.put(dstIp)                   // Dest IP

        // UDP header
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)                  // UDP checksum (skip)

        // Payload
        bb.put(payload, 0, payLen)
        return pkt
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.u16(off: Int): Int =
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
