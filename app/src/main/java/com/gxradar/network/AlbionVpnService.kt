package com.gxradar.network

import android.app.NotificationManager
import android.content.Intent
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

/**
 * AlbionVpnService
 *
 * KEY: addAllowedApplication("com.albiononline")
 *   Only Albion's UDP traffic enters TUN — all other apps bypass completely.
 *   This is why internet works normally while radar captures packets.
 *
 * Stop: MainActivity calls stopService() directly — no broadcast needed.
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

        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)
    }

    private var dispatcher:      EventDispatcher?      = null
    private var discoveryLogger: DiscoveryLogger?      = null
    private var tunPfd:          ParcelFileDescriptor? = null

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // Safe init — storage errors must not crash the service
        val ctx = this
        runCatching { discoveryLogger = DiscoveryLogger(ctx) }
        val logger = discoveryLogger
        if (logger != null) {
            runCatching { dispatcher = EventDispatcher(logger) }
        }
        // If logger init failed, dispatcher stays null — packets are captured
        // but entities won't be stored until a zone is entered again after fix.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("Starting…"))
        scope.launch {
            try { runCapture() }
            catch (e: CancellationException) { /* normal stop */ }
            catch (e: Exception) { Log.e(TAG, "capture error: ${e.message}") }
        }
        return START_NOT_STICKY
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
                .addAllowedApplication(ALBION_PACKAGE)
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
            if (protocol != 17 || len < ihl + 8) continue

            val srcPort = inBuf.u16(ihl)
            val dstPort = inBuf.u16(ihl + 2)
            val payOff  = ihl + 8
            val payLen  = len - payOff
            if (payLen <= 0) continue

            // Parse Photon if Albion port
            if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
                albionCount.incrementAndGet()
                try {
                    val payload = inBuf.copyOfRange(payOff, payOff + payLen)
                    PhotonParser.parse(payload).forEach { dispatcher?.dispatch(it) }
                    if (albionCount.get() % 50 == 0L) updateNotif()
                } catch (e: Exception) {
                    Log.v(TAG, "photon: ${e.message}")
                }
            }

            // Forward UDP via protected socket
            try {
                val dstIpBytes = inBuf.copyOfRange(16, 20)
                val dstIp      = InetAddress.getByAddress(dstIpBytes)
                val key        = "$srcPort:${dstIp.hostAddress}:$dstPort"

                val sock = udpSockets.getOrPut(key) {
                    DatagramSocket().also { s -> protect(s) }
                }

                sock.send(DatagramPacket(inBuf, payOff, payLen, dstIp, dstPort))

                // Check for response (non-blocking)
                sock.soTimeout = 1
                val respBuf = ByteArray(MTU)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                try {
                    sock.receive(respPkt)
                    val srcIpInt = ByteBuffer.wrap(inBuf).getInt(12)
                    val localIp  = ByteArray(4).also {
                        it[0] = (srcIpInt shr 24).toByte()
                        it[1] = (srcIpInt shr 16).toByte()
                        it[2] = (srcIpInt shr 8).toByte()
                        it[3] = srcIpInt.toByte()
                    }
                    val response = buildIpPacket(
                        srcIp   = dstIpBytes,
                        dstIp   = localIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        payload = respBuf,
                        payLen  = respPkt.length
                    )
                    if (response != null) tunOut.write(response)
                } catch (_: java.net.SocketTimeoutException) { }
            } catch (e: Exception) {
                udpSockets.remove("$srcPort")?.runCatching { close() }
            }
        }
    }

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
        bb.put(0x45.toByte()); bb.put(0)
        bb.putShort(totalLen.toShort())
        bb.putShort(0); bb.putShort(0x4000.toShort())
        bb.put(64); bb.put(17); bb.putShort(0)
        bb.put(srcIp); bb.put(dstIp)
        bb.putShort(srcPort.toShort()); bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort()); bb.putShort(0)
        bb.put(payload, 0, payLen)
        return pkt
    }

    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true)
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
