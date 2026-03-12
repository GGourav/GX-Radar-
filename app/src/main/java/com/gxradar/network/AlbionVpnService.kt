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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG       = "AlbionVpnService"
        const val ACTION_START      = "com.gxradar.vpn.START"
        const val ACTION_STOP       = "com.gxradar.vpn.STOP"
        const val ALBION_PORT       = 5056
        const val NOTIF_ID          = 1001
        private const val MTU       = 32767
        private const val TUN_IP    = "10.99.0.1"
        private const val FLOW_TTL  = 60_000L   // remove idle flows after 60s

        // Shared counters read by overlay
        val packetCount  = AtomicLong(0)
        val albionCount  = AtomicLong(0)
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dispatcher: EventDispatcher
    private lateinit var discoveryLogger: DiscoveryLogger

    // flow key = srcPort (Int), value = DatagramSocket
    private val flows = ConcurrentHashMap<Int, FlowEntry>()

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopSelf()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        discoveryLogger = DiscoveryLogger(this)
        dispatcher      = EventDispatcher(discoveryLogger)
        val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif("Initialising VPN…"))
        serviceScope.launch { startCapture() }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        closeAllFlows()
        closeTun()
        EntityStore.clear()
        packetCount.set(0)
        albionCount.set(0)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ── TUN setup ─────────────────────────────────────────────────────────────

    private suspend fun startCapture() {
        val pfd = withContext(Dispatchers.IO) { buildTun() }
        if (pfd == null) { Log.e(TAG, "TUN failed"); stopSelf(); return }
        tunPfd = pfd
        notify("Capturing Albion port $ALBION_PORT")
        // Cleanup idle flows every 30s
        serviceScope.launch {
            while (isActive) {
                delay(30_000)
                cleanIdleFlows()
            }
        }
        runCaptureLoop(pfd)
    }

    private fun buildTun(): ParcelFileDescriptor? = runCatching {
        Builder()
            .setSession("GX Radar")
            .addAddress(TUN_IP, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(MTU)
            .setBlocking(true)
            .establish()
    }.getOrElse { e -> Log.e(TAG, "establish() failed", e); null }

    private fun closeTun() = runCatching { tunPfd?.close(); tunPfd = null }

    // ── Capture loop ──────────────────────────────────────────────────────────

    private suspend fun runCaptureLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input  = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buf    = ByteArray(MTU)

            try {
                while (isActive) {
                    val len = input.read(buf).takeIf { it > 20 } ?: continue
                    packetCount.incrementAndGet()

                    // IPv4 only
                    if ((buf[0].toInt() and 0xFF) shr 4 != 4) continue
                    val ihl      = (buf[0].toInt() and 0x0F) * 4
                    val protocol = buf[9].toInt() and 0xFF
                    if (protocol != 17 || len < ihl + 8) continue   // UDP only

                    val srcPort  = buf.beUShort(ihl)
                    val dstPort  = buf.beUShort(ihl + 2)
                    val udpLen   = buf.beUShort(ihl + 4)
                    val payOff   = ihl + 8
                    val payLen   = minOf(udpLen - 8, len - payOff).takeIf { it > 0 } ?: continue

                    val dstIpBytes = buf.copyOfRange(16, 20)
                    val srcIpBytes = buf.copyOfRange(12, 16)
                    val payload    = buf.copyOfRange(payOff, payOff + payLen)

                    // Parse Albion packets (both directions)
                    if ((srcPort == ALBION_PORT || dstPort == ALBION_PORT) && payLen >= 12) {
                        albionCount.incrementAndGet()
                        parsePhoton(payload)
                        notify("Pkts: ${packetCount.get()}  Albion: ${albionCount.get()}  Entities: ${EntityStore.size()}")
                    }

                    // Forward via protected socket
                    val flow = getOrCreateFlow(srcPort, srcIpBytes, output) ?: continue
                    try {
                        val dst = InetAddress.getByAddress(dstIpBytes)
                        flow.socket.send(DatagramPacket(payload, payLen, dst, dstPort))
                        flow.lastUsed = System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.v(TAG, "fwd send: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Capture cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Capture error", e)
            }
        }

    // ── Flow management ───────────────────────────────────────────────────────

    private fun getOrCreateFlow(
        srcPort: Int,
        srcIpBytes: ByteArray,
        tunOutput: FileOutputStream
    ): FlowEntry? {
        flows[srcPort]?.let { return it }
        return try {
            // CRITICAL: create socket first, protect() immediately, then use
            val sock = DatagramSocket()
            protect(sock)   // must happen before any network I/O
            val entry = FlowEntry(sock, srcIpBytes.copyOf())
            flows[srcPort] = entry

            // Receive coroutine — server → device
            entry.job = serviceScope.launch(Dispatchers.IO) {
                val rbuf   = ByteArray(MTU)
                val packet = DatagramPacket(rbuf, rbuf.size)
                while (isActive) {
                    try {
                        sock.soTimeout = 5000
                        sock.receive(packet)
                        val pLen = packet.length
                        if (pLen < 1) continue

                        // Parse server→client Albion events (this is where entity spawns come from)
                        if (packet.port == ALBION_PORT && pLen >= 12) {
                            parsePhoton(rbuf.copyOf(pLen))
                        }

                        // Write response back to TUN so the game receives it
                        val ipPkt = buildIpUdp(
                            srcIp   = packet.address.address,
                            dstIp   = srcIpBytes,
                            srcPort = packet.port,
                            dstPort = srcPort,
                            payload = rbuf,
                            payLen  = pLen
                        )
                        synchronized(tunOutput) { tunOutput.write(ipPkt) }
                        entry.lastUsed = System.currentTimeMillis()

                    } catch (e: CancellationException) { break }
                    catch (e: java.net.SocketTimeoutException) { /* normal, loop */ }
                    catch (e: Exception) {
                        if (isActive) Log.v(TAG, "recv: ${e.message}")
                    }
                }
            }
            entry
        } catch (e: Exception) {
            Log.e(TAG, "createFlow failed", e)
            null
        }
    }

    private fun cleanIdleFlows() {
        val now = System.currentTimeMillis()
        val iter = flows.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value.lastUsed > FLOW_TTL) {
                e.value.close()
                iter.remove()
            }
        }
    }

    private fun closeAllFlows() {
        flows.values.forEach { it.close() }
        flows.clear()
    }

    // ── Photon ────────────────────────────────────────────────────────────────

    private fun parsePhoton(payload: ByteArray) {
        try {
            PhotonParser.parse(payload).forEach { dispatcher.dispatch(it) }
        } catch (e: Exception) {
            Log.v(TAG, "photon: ${e.message}")
        }
    }

    // ── IP+UDP packet builder ─────────────────────────────────────────────────

    private fun buildIpUdp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payLen: Int
    ): ByteArray {
        val udpLen = 8 + payLen
        val ipLen  = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        // IP header
        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(17); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        // UDP header
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort())
        b.putShort(0)
        b.put(payload, 0, payLen)
        return b.array()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.beUShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar — Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true).build()

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }

    // ── FlowEntry ─────────────────────────────────────────────────────────────

    private inner class FlowEntry(
        val socket: DatagramSocket,
        val srcIp: ByteArray,
        var lastUsed: Long = System.currentTimeMillis(),
        var job: kotlinx.coroutines.Job? = null
    ) {
        fun close() {
            job?.cancel()
            runCatching { socket.close() }
        }
    }
}
