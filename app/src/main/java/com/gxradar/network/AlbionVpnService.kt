package com.gxradar.network

import android.app.NotificationManager
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AlbionVpnService — Step 2: full TUN capture + bidirectional UDP forwarding
 *
 * Architecture:
 *
 *   Android App (Albion)
 *        │ write
 *        ▼
 *   TUN Interface  ──read──▶  [our capture loop]
 *                                    │
 *                    port 5056? ─────┤─────▶ PhotonParser ──▶ EventDispatcher
 *                                    │                              │
 *                                    ▼                       EntityStore
 *                             protect()ed DatagramSocket
 *                                    │ send
 *                                    ▼
 *                             Real Internet (Albion Server)
 *                                    │ receive
 *                                    ▼
 *                    port 5056? ─────┤─────▶ PhotonParser (server→client events)
 *                                    │
 *                             Build IP+UDP response packet
 *                                    │ write
 *                                    ▼
 *                             TUN Interface ──▶ Android App (Albion)
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG         = "AlbionVpnService"
        const val ACTION_START        = "com.gxradar.vpn.START"
        const val ACTION_STOP         = "com.gxradar.vpn.STOP"
        const val ALBION_PORT         = 5056
        const val NOTIF_ID            = 1001
        private const val MTU         = 32767
        private const val TUN_IP      = "10.99.0.1"
        private const val MAX_FLOWS   = 128   // cap active UDP flows
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dispatcher: EventDispatcher
    private lateinit var discoveryLogger: DiscoveryLogger

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        discoveryLogger = DiscoveryLogger(this)
        dispatcher      = EventDispatcher(discoveryLogger)
        val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif("Initialising…"))
        serviceScope.launch { startCapture() }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        closeTun()
        EntityStore.clear()
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ─────────────────────────────────────────────────────────────────────────
    // TUN establishment
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun startCapture() {
        val pfd = withContext(Dispatchers.IO) { buildTun() }
        if (pfd == null) {
            Log.e(TAG, "TUN establishment failed — stopping")
            stopSelf()
            return
        }
        tunPfd = pfd
        notify("Capturing — port $ALBION_PORT")
        Log.i(TAG, "TUN ready. Discovery log → ${discoveryLogger.getLogPath()}")
        runCaptureLoop(pfd)
    }

    private fun buildTun(): ParcelFileDescriptor? = runCatching {
        Builder()
            .setSession("GX Radar")
            .addAddress(TUN_IP, 32)
            .addRoute("0.0.0.0", 0)      // all IPv4 through TUN
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(MTU)
            .setBlocking(true)
            .establish()
    }.getOrElse { e -> Log.e(TAG, "Builder.establish() failed", e); null }

    private fun closeTun() {
        runCatching { tunPfd?.close() }
        tunPfd = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture loop — reads raw IP packets from TUN
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runCaptureLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val tunIn  = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(MTU)

            // Flow table: device-srcPort → ForwardFlow
            val flows  = LinkedHashMap<Int, ForwardFlow>()

            try {
                while (isActive) {
                    val len = tunIn.read(buffer).takeIf { it > 0 } ?: continue

                    // Require minimum IPv4 + UDP headers
                    if (len < 28) continue
                    val version = (buffer[0].toInt() and 0xFF) shr 4
                    if (version != 4) continue

                    val ihl      = (buffer[0].toInt() and 0x0F) shl 2
                    val protocol = buffer[9].toInt() and 0xFF
                    if (protocol != 17) continue   // UDP only

                    processUdpPacket(buffer, len, ihl, tunOut, flows)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Capture loop cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error", e)
            } finally {
                flows.values.forEach { it.close() }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // UDP packet processing + forwarding
    // ─────────────────────────────────────────────────────────────────────────

    private fun processUdpPacket(
        buf: ByteArray,
        len: Int,
        ihl: Int,
        tunOut: FileOutputStream,
        flows: LinkedHashMap<Int, ForwardFlow>
    ) {
        if (len < ihl + 8) return

        val srcPort = buf.beShort(ihl + 0)
        val dstPort = buf.beShort(ihl + 2)
        val payOff  = ihl + 8
        val payLen  = len - payOff
        if (payLen <= 0) return

        val srcIp = buf.copyOfRange(12, 16)   // source IP from IP header
        val dstIp = buf.copyOfRange(16, 20)   // destination IP

        // ── Photon parse — client→server direction ──────────────────────────
        if (dstPort == ALBION_PORT && payLen >= 12) {
            parsePhoton(buf, payOff, payLen)
        }

        // ── UDP forward ─────────────────────────────────────────────────────
        val flow = flows.getOrCreate(srcPort) { newFlow(srcPort, srcIp, tunOut) }
            ?: return

        val payload = buf.copyOfRange(payOff, payOff + payLen)
        try {
            val dst = InetAddress.getByAddress(dstIp)
            flow.socket.send(DatagramPacket(payload, payload.size, dst, dstPort))
        } catch (e: Exception) {
            Log.v(TAG, "Forward send: ${e.message}")
        }
    }

    private fun newFlow(
        srcPort: Int,
        srcIp: ByteArray,
        tunOut: FileOutputStream
    ): ForwardFlow? {
        return try {
            val sock = DatagramSocket()
            protect(sock)   // critical: exempts socket from the VPN loop
            val flow = ForwardFlow(sock, srcPort, srcIp.copyOf())

            // Receive coroutine — handles server → device direction
            flow.receiveJob = serviceScope.launch(Dispatchers.IO) {
                val rbuf   = ByteArray(MTU)
                val packet = DatagramPacket(rbuf, rbuf.size)
                while (isActive) {
                    try {
                        sock.receive(packet)
                        val payloadLen = packet.length
                        if (payloadLen < 1) continue

                        // ── Photon parse — server→client direction (events!) ───
                        if (packet.port == ALBION_PORT && payloadLen >= 12) {
                            parsePhoton(rbuf, 0, payloadLen)
                        }

                        // ── Build response IP+UDP and write back to TUN ────────
                        val ipPkt = buildIpUdpPacket(
                            srcIp   = packet.address.address,   // server IP
                            dstIp   = InetAddress.getByAddress(srcIp).address,
                            srcPort = packet.port,
                            dstPort = srcPort,
                            payload = rbuf.copyOf(payloadLen)
                        )
                        synchronized(tunOut) { tunOut.write(ipPkt) }

                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        if (isActive) Log.v(TAG, "Forward recv: ${e.message}")
                    }
                }
            }
            flow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create flow for srcPort=$srcPort", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Photon processing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parsePhoton(buf: ByteArray, offset: Int, length: Int) {
        try {
            val payload = if (offset == 0 && length == buf.size) buf
                          else buf.copyOfRange(offset, offset + length)
            val messages = PhotonParser.parse(payload)
            for (msg in messages) dispatcher.dispatch(msg)
        } catch (e: Exception) {
            Log.v(TAG, "Photon parse: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP + UDP packet builder (for TUN responses)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildIpUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header (20 bytes, no options)
        b.put(0x45.toByte())             // Version=4, IHL=5
        b.put(0)                          // DSCP/ECN
        b.putShort(ipLen.toShort())
        b.putShort(0)                     // Identification
        b.putShort(0x4000.toShort())      // Flags: Don't Fragment
        b.put(64)                         // TTL
        b.put(17)                         // Protocol: UDP
        b.putShort(0)                     // Checksum — kernel recalculates; 0 is fine on TUN
        b.put(srcIp)
        b.put(dstIp)

        // UDP header (8 bytes)
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort())
        b.putShort(0)                     // UDP checksum — TUN ignores

        b.put(payload)
        return b.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar — Capture Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true)
            .build()

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Read a Big-Endian unsigned short at [offset] */
    private fun ByteArray.beShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    /** Get-or-create with LRU eviction when table is full */
    private fun LinkedHashMap<Int, ForwardFlow>.getOrCreate(
        key: Int,
        factory: () -> ForwardFlow?
    ): ForwardFlow? {
        this[key]?.let { return it }
        if (size >= MAX_FLOWS) {
            val oldest = entries.iterator()
            if (oldest.hasNext()) { oldest.next().value.close(); oldest.remove() }
        }
        val flow = factory() ?: return null
        this[key] = flow
        return flow
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ForwardFlow — one protected UDP socket per device source port
    // ─────────────────────────────────────────────────────────────────────────

    private inner class ForwardFlow(
        val socket: DatagramSocket,
        val srcPort: Int,
        val srcIp: ByteArray
    ) {
        var receiveJob: Job? = null

        fun close() {
            receiveJob?.cancel()
            runCatching { socket.close() }
        }
    }
}
