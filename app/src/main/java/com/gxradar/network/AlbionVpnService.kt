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
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AlbionVpnService — FINAL correct implementation
 *
 * KEY FIX (confirmed by decompiling ALBION_HACK APK):
 *   addAllowedApplication("com.albiononline")
 *   → Only Albion Online traffic enters our TUN
 *   → All other apps bypass VPN entirely (internet works normally)
 *   → No system-wide TCP/DNS proxy needed
 *
 * Architecture:
 *   TUN read loop → parse raw IP packets from Albion only
 *     UDP port 5056  → NIO DatagramChannel (protected) + Photon parser
 *     TCP (any port) → NIO SocketChannel (protected) relay (login/HTTPS)
 *   Selector loop    → incoming responses → write back to TUN
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG           = "AlbionVpnService"
        const val  ACTION_START         = "com.gxradar.vpn.START"
        const val  ACTION_STOP          = "com.gxradar.vpn.STOP"
        const val  ALBION_PACKAGE       = "com.albiononline"
        const val  ALBION_PORT          = 5056
        const val  NOTIF_ID             = 1001
        private const val MTU           = 32767
        private const val TUN_IP        = "10.8.0.2"   // matches working APK
        private const val TUN_PREFIX    = 32
        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)
    }

    private var tunPfd:    ParcelFileDescriptor? = null
    private var tunOut:    FileOutputStream?     = null
    private var selector:  Selector?             = null
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpMap     = ConcurrentHashMap<Int, UdpEntry>()
    private val tcpMap     = ConcurrentHashMap<Int, TcpEntry>()
    private lateinit var dispatcher:      EventDispatcher
    private lateinit var discoveryLogger: DiscoveryLogger

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_STOP) stopSelf()
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        discoveryLogger = DiscoveryLogger(this)
        dispatcher      = EventDispatcher(discoveryLogger)
        val flags = if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif("Starting…"))
        scope.launch { runCapture() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { selector?.close() }
        udpMap.values.forEach { runCatching { it.channel.close() } }; udpMap.clear()
        tcpMap.values.forEach { it.close() };                          tcpMap.clear()
        runCatching { tunPfd?.close() }; tunPfd = null
        EntityStore.clear()
        packetCount.set(0); albionCount.set(0)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ─── TUN setup ────────────────────────────────────────────────────────────

    private suspend fun runCapture() {
        val pfd = withContext(Dispatchers.IO) {
            runCatching {
                Builder()
                    .setSession("GX Radar")
                    .addAddress(TUN_IP, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(MTU)
                    .setBlocking(true)
                    // ★ THE KEY FIX: only route Albion Online through TUN
                    .addAllowedApplication(ALBION_PACKAGE)
                    .establish()
            }.getOrNull()
        }
        if (pfd == null) {
            Log.e(TAG, "TUN establish failed")
            stopSelf(); return
        }
        tunPfd   = pfd
        tunOut   = FileOutputStream(pfd.fileDescriptor)
        selector = Selector.open()
        notify("Capturing Albion port $ALBION_PORT")

        scope.launch(Dispatchers.IO) { runSelectorLoop() }
        runTunReadLoop(pfd)
    }

    // ─── TUN read loop ─────────────────────────────────────────────────────────
    // Only Albion Online packets arrive here (addAllowedApplication ensures this)

    private suspend fun runTunReadLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf   = ByteArray(MTU)
            try {
                while (isActive) {
                    val len = input.read(buf).takeIf { it > 20 } ?: continue
                    packetCount.incrementAndGet()
                    if ((buf[0].toInt() and 0xF0) != 0x40) continue   // IPv4 only
                    val ihl   = (buf[0].toInt() and 0x0F) * 4
                    val proto = buf[9].toInt() and 0xFF
                    if (len < ihl + 8) continue
                    val srcIp = buf.copyOfRange(12, 16)
                    val dstIp = buf.copyOfRange(16, 20)
                    when (proto) {
                        17 -> handleUdp(buf, len, ihl, srcIp, dstIp)
                        6  -> handleTcp(buf, len, ihl, srcIp, dstIp)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "TUN read cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TUN read error", e)
            }
        }

    // ─── UDP (NIO DatagramChannel) ────────────────────────────────────────────

    private fun handleUdp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)
        val payOff  = ihl + 8
        val payLen  = len - payOff
        if (payLen <= 0) return

        // Parse Photon on ALL outgoing Albion UDP (client→server are OperationRequests,
        // but server→client events arrive via selector/readUdp below)
        if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
            albionCount.incrementAndGet()
            parsePhoton(buf, payOff, payLen)
            updateNotif()
        }

        // Create or reuse protected DatagramChannel keyed by source port
        val entry = udpMap.getOrPut(srcPort) {
            runCatching {
                val ch = DatagramChannel.open()
                protect(ch.socket())              // protect BEFORE connect
                ch.configureBlocking(false)
                ch.connect(InetSocketAddress(
                    java.net.InetAddress.getByAddress(dstIp), dstPort
                ))
                val e = UdpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
                selector?.wakeup()
                ch.register(selector, SelectionKey.OP_READ, e)
                e
            }.getOrElse { return }
        }

        // Forward payload to real server
        runCatching {
            entry.channel.write(ByteBuffer.wrap(buf, payOff, payLen))
        }.onFailure {
            udpMap.remove(srcPort)?.channel?.runCatching { close() }
        }
    }

    // ─── TCP (NIO SocketChannel) ──────────────────────────────────────────────

    private fun handleTcp(
        buf: ByteArray, len: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ) {
        if (len < ihl + 20) return
        val srcPort = buf.u16(ihl)
        val dstPort = buf.u16(ihl + 2)
        val tcpOff  = ((buf[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags   = buf[ihl + 13].toInt() and 0xFF
        val isSyn   = flags and 0x02 != 0
        val isFin   = flags and 0x01 != 0
        val isRst   = flags and 0x04 != 0
        val payOff  = ihl + tcpOff
        val payLen  = (len - payOff).coerceAtLeast(0)

        when {
            isRst || isFin -> { tcpMap.remove(srcPort)?.close() }
            isSyn -> scope.launch(Dispatchers.IO) {
                openTcpChannel(srcIp, srcPort, dstIp, dstPort)
            }
            payLen > 0 -> {
                val entry = tcpMap[srcPort] ?: return
                runCatching {
                    val d = ByteBuffer.wrap(buf, payOff, payLen)
                    while (d.hasRemaining()) entry.channel.write(d)
                }.onFailure { tcpMap.remove(srcPort)?.close() }
            }
        }
    }

    private fun openTcpChannel(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int
    ) {
        runCatching {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            protect(ch.socket())          // protect BEFORE connect
            val entry = TcpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
            tcpMap[srcPort] = entry
            ch.connect(InetSocketAddress(
                java.net.InetAddress.getByAddress(dstIp), dstPort
            ))
            selector?.wakeup()
            ch.register(selector, SelectionKey.OP_CONNECT, entry)
        }.onFailure {
            Log.v(TAG, "TCP open: ${it.message}")
            tcpMap.remove(srcPort)
        }
    }

    // ─── NIO Selector loop ────────────────────────────────────────────────────

    private fun runSelectorLoop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocate(MTU)
        try {
            while (scope.isActive) {
                if (sel.select(500L) == 0) continue
                val keys = sel.selectedKeys().toSet()
                sel.selectedKeys().clear()
                for (key in keys) {
                    if (!key.isValid) continue
                    when {
                        key.isReadable    -> onReadable(key, buf)
                        key.isConnectable -> onConnectable(key)
                    }
                }
            }
        } catch (e: ClosedSelectorException) {
            Log.d(TAG, "Selector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Selector error", e)
        }
    }

    private fun onReadable(key: SelectionKey, buf: ByteBuffer) {
        when (val att = key.attachment()) {
            is UdpEntry -> readUdp(att, buf)
            is TcpEntry -> readTcp(att, buf)
        }
    }

    private fun onConnectable(key: SelectionKey) {
        val entry = key.attachment() as? TcpEntry ?: return
        try {
            if (entry.channel.finishConnect()) key.interestOps(SelectionKey.OP_READ)
            else tcpMap.remove(entry.srcPort)?.close()
        } catch (e: Exception) {
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    private fun readUdp(entry: UdpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }

            // Server→client Albion packets: parse Photon here
            if (entry.dstPort == ALBION_PORT && n >= 12) {
                albionCount.incrementAndGet()
                parsePhoton(payload, 0, n)
                updateNotif()
            }

            // Write response back to TUN
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return
            val pkt = buildUdpPacket(serverIp, entry.srcIp,
                entry.dstPort, entry.srcPort, payload)
            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            Log.v(TAG, "UDP read: ${e.message}")
        }
    }

    private fun readTcp(entry: TcpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n < 0) { tcpMap.remove(entry.srcPort)?.close(); return }
            if (n == 0) return
            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return
            val pkt = buildTcpPacket(serverIp, entry.srcIp,
                entry.dstPort, entry.srcPort, payload)
            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            Log.v(TAG, "TCP read: ${e.message}")
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    // ─── Packet builders ──────────────────────────────────────────────────────

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        // IP header
        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(17)
        val csumPos = b.position(); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        val arr = b.array()
        val cs = ipChecksum(arr, 0, 20)
        arr[csumPos] = (cs shr 8).toByte(); arr[csumPos+1] = cs.toByte()
        // UDP header
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort()); b.putShort(0)  // checksum=0 (disabled for IPv4)
        b.put(payload)
        return arr
    }

    private fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val ipLen  = 20 + tcpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(6)
        val ipCsumPos = b.position(); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        val arr = b.array()
        val ipCs = ipChecksum(arr, 0, 20)
        arr[ipCsumPos] = (ipCs shr 8).toByte(); arr[ipCsumPos+1] = ipCs.toByte()
        // TCP header (PSH+ACK)
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putInt(1); b.putInt(1)   // seq/ack simplified
        b.put(0x50.toByte())       // data offset=5 (20 bytes)
        b.put(0x18.toByte())       // PSH+ACK
        b.putShort(65535.toShort())
        b.putShort(0)              // checksum (0=disabled ok for TUN)
        b.putShort(0)              // urgent
        b.put(payload)
        return arr
    }

    private fun ipChecksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 != 0) sum += (data[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    // ─── Photon parser ────────────────────────────────────────────────────────

    private fun parsePhoton(buf: ByteArray, off: Int, len: Int) {
        try {
            val payload = if (off == 0 && len == buf.size) buf
                          else buf.copyOfRange(off, off + len)
            PhotonParser.parse(payload).forEach { dispatcher.dispatch(it) }
        } catch (e: Exception) {
            Log.v(TAG, "photon: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun ByteArray.u16(off: Int) =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off+1].toInt() and 0xFF)

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, GXRadarApplication.CHANNEL_VPN)
            .setContentTitle("GX Radar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setOngoing(true).setSilent(true).build()

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }

    private fun updateNotif() {
        notify("PKT ${packetCount.get()}  ALB ${albionCount.get()}  ENT ${EntityStore.size()}")
    }

    // ─── Data classes ─────────────────────────────────────────────────────────

    private data class UdpEntry(
        val channel: DatagramChannel,
        val srcIp:   ByteArray,
        val srcPort: Int,
        val dstPort: Int
    )

    private inner class TcpEntry(
        val channel: SocketChannel,
        val srcIp:   ByteArray,
        val srcPort: Int,
        val dstPort: Int
    ) {
        fun close() = runCatching { channel.close() }
    }
}
