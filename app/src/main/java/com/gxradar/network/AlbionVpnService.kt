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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG    = "AlbionVpnService"
        const val ACTION_START   = "com.gxradar.vpn.START"
        const val ACTION_STOP    = "com.gxradar.vpn.STOP"
        const val ALBION_PORT    = 5056
        const val NOTIF_ID       = 1001
        private const val MTU    = 32767
        private const val TUN_IP = "10.99.0.1"
        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dispatcher: EventDispatcher
    private lateinit var discoveryLogger: DiscoveryLogger

    // UDP: srcPort → UdpFlow
    private val udpFlows = ConcurrentHashMap<Int, UdpFlow>()
    // TCP: "srcIP:srcPort→dstIP:dstPort" → TcpSession
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i?.action == ACTION_STOP) stopSelf() }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

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
        scope.launch { startCapture() }
        return START_NOT_STICKY  // ← KEY: don't restart if killed
    }

    override fun onDestroy() {
        scope.cancel()
        udpFlows.values.forEach { it.close() };  udpFlows.clear()
        tcpSessions.values.forEach { it.close() }; tcpSessions.clear()
        runCatching { tunPfd?.close() };  tunPfd = null
        EntityStore.clear()
        packetCount.set(0); albionCount.set(0)
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    override fun onRevoke() = stopSelf()

    // ── TUN setup ───────────────────────────────────────────────────────────

    private suspend fun startCapture() {
        val pfd = withContext(Dispatchers.IO) {
            runCatching {
                Builder()
                    .setSession("GX Radar")
                    .addAddress(TUN_IP, 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .establish()
            }.getOrNull()
        }
        if (pfd == null) { Log.e(TAG, "TUN establish failed"); stopSelf(); return }
        tunPfd = pfd
        notify("Capturing port $ALBION_PORT")
        // Idle flow cleanup
        scope.launch {
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()
                udpFlows.entries.removeIf { (_, v) ->
                    (now - v.lastUsed > 60_000L).also { if (it) v.close() }
                }
            }
        }
        runCaptureLoop(pfd)
    }

    private suspend fun runCaptureLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input  = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buf    = ByteArray(MTU)
            try {
                while (isActive) {
                    val len = input.read(buf).takeIf { it > 20 } ?: continue
                    packetCount.incrementAndGet()
                    if ((buf[0].toInt() and 0xFF) shr 4 != 4) continue  // IPv4 only
                    val ihl      = (buf[0].toInt() and 0x0F) * 4
                    val protocol = buf[9].toInt() and 0xFF
                    if (len < ihl + 8) continue
                    when (protocol) {
                        17 -> handleUdp(buf, len, ihl, output)
                        6  -> handleTcp(buf, len, ihl, output)
                        // other protocols: silently skip
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Capture loop cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error", e)
            }
        }

    // ── UDP ───────────────────────────────────────────────────────────────────

    private fun handleUdp(buf: ByteArray, len: Int, ihl: Int, tunOut: FileOutputStream) {
        val srcPort = buf.uShort(ihl)
        val dstPort = buf.uShort(ihl + 2)
        val payOff  = ihl + 8
        val payLen  = len - payOff
        if (payLen <= 0) return
        val srcIp   = buf.copyOfRange(12, 16)
        val dstIp   = buf.copyOfRange(16, 20)
        val payload = buf.copyOfRange(payOff, payOff + payLen)

        if ((dstPort == ALBION_PORT || srcPort == ALBION_PORT) && payLen >= 12) {
            albionCount.incrementAndGet()
            parsePhoton(payload)
            notify("PKT ${packetCount.get()}  ALB ${albionCount.get()}  ENT ${EntityStore.size()}")
        }

        // Get or create protected UDP flow
        val flow = udpFlows[srcPort] ?: run {
            val sock = DatagramSocket()
            if (!protect(sock)) { sock.close(); return }
            val f = UdpFlow(sock, srcIp.copyOf())
            f.receiveJob = scope.launch(Dispatchers.IO) {
                val rbuf   = ByteArray(MTU)
                val packet = DatagramPacket(rbuf, rbuf.size)
                while (isActive && !sock.isClosed) {
                    try {
                        packet.setLength(rbuf.size)  // ← reset length before each receive
                        sock.soTimeout = 10_000
                        sock.receive(packet)
                        val pLen = packet.length
                        if (pLen < 1) continue
                        if (packet.port == ALBION_PORT && pLen >= 12)
                            parsePhoton(rbuf.copyOf(pLen))
                        val ipPkt = buildUdpPacket(
                            srcIp   = packet.address.address,
                            dstIp   = srcIp,
                            srcPort = packet.port,
                            dstPort = srcPort,
                            payload = rbuf, payLen = pLen
                        )
                        synchronized(tunOut) { tunOut.write(ipPkt) }
                        f.lastUsed = System.currentTimeMillis()
                    } catch (e: CancellationException) { break }
                    catch (e: java.net.SocketTimeoutException) { /* idle timeout, loop */ }
                    catch (e: Exception) { if (isActive) Log.v(TAG, "udp recv: ${e.message}") }
                }
            }
            udpFlows[srcPort] = f
            f
        }
        try {
            flow.socket.send(DatagramPacket(payload, payLen, InetAddress.getByAddress(dstIp), dstPort))
            flow.lastUsed = System.currentTimeMillis()
        } catch (e: Exception) {
            udpFlows.remove(srcPort)?.close()
        }
    }

    // ── TCP ───────────────────────────────────────────────────────────────────
    //
    // Albion login (live.albiononline.com) and chat both use TCP.
    // Without this relay, the game can never authenticate and connect.
    // We do a transparent MITM: SYN → connect Java Socket → send SYN-ACK →
    // relay data in both directions via raw IP+TCP packets over TUN.

    private fun handleTcp(buf: ByteArray, len: Int, ihl: Int, tunOut: FileOutputStream) {
        if (len < ihl + 20) return
        val srcIp     = buf.copyOfRange(12, 16)
        val dstIp     = buf.copyOfRange(16, 20)
        val srcPort   = buf.uShort(ihl)
        val dstPort   = buf.uShort(ihl + 2)
        val seqNum    = buf.uInt32(ihl + 4)
        val tcpHdrLen = ((buf[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags     = buf[ihl + 13].toInt() and 0xFF
        val isSyn     = flags and 0x02 != 0
        val isAck     = flags and 0x10 != 0
        val isFin     = flags and 0x01 != 0
        val isRst     = flags and 0x04 != 0
        val payOff    = ihl + tcpHdrLen
        val payLen    = (len - payOff).coerceAtLeast(0)
        val key       = "${srcIp.ip()}:$srcPort-${dstIp.ip()}:$dstPort"

        when {
            isRst -> {
                tcpSessions.remove(key)?.close()
            }
            isFin -> {
                val s = tcpSessions.remove(key)
                s?.let {
                    it.clientSeq++
                    sendTcp(tunOut, dstIp, srcIp, dstPort, srcPort,
                            it.mySeq, it.clientSeq, 0x11)  // FIN+ACK
                    it.close()
                }
            }
            isSyn && !isAck -> {
                // New TCP connection: open protected Socket to server
                scope.launch {
                    openTcpSession(key, srcIp, srcPort, dstIp, dstPort, seqNum, tunOut)
                }
            }
            else -> {
                val s = tcpSessions[key] ?: return
                if (!s.established) return
                if (payLen > 0) {
                    try {
                        s.serverOut.write(buf, payOff, payLen)
                        s.serverOut.flush()
                        s.clientSeq += payLen
                        // Send ACK back to client
                        sendTcp(tunOut, dstIp, srcIp, dstPort, srcPort,
                                s.mySeq, s.clientSeq, 0x10)
                    } catch (e: Exception) {
                        tcpSessions.remove(key)?.close()
                    }
                }
            }
        }
    }

    private suspend fun openTcpSession(
        key: String,
        clientIp: ByteArray, clientPort: Int,
        serverIp: ByteArray, serverPort: Int,
        clientSynSeq: Long, tunOut: FileOutputStream
    ) {
        val sock = Socket()
        if (!protect(sock)) { sock.close(); return }
        try {
            withContext(Dispatchers.IO) {
                sock.connect(InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort), 5000)
            }
        } catch (e: Exception) {
            Log.v(TAG, "TCP connect ${serverIp.ip()}:$serverPort failed: ${e.message}")
            sock.close()
            // Send RST to client so it doesn't hang
            sendTcp(tunOut, serverIp, clientIp, serverPort, clientPort,
                    0L, clientSynSeq + 1, 0x04)
            return
        }

        val myInit    = 1000L
        val clientSeq = clientSynSeq + 1L  // SYN counts as 1 byte
        val session   = TcpSession(
            socket    = sock,
            serverOut = sock.getOutputStream(),
            clientSeq = clientSeq,
            mySeq     = myInit + 1L
        )
        tcpSessions[key] = session

        // Send SYN-ACK to client over TUN
        sendTcp(tunOut, serverIp, clientIp, serverPort, clientPort,
                myInit, clientSeq, 0x12)  // SYN+ACK

        // Server→client receive coroutine
        session.receiveJob = scope.launch(Dispatchers.IO) {
            val rbuf = ByteArray(16_384)
            try {
                while (isActive && !sock.isClosed) {
                    val n = sock.getInputStream().read(rbuf)
                    if (n <= 0) break
                    sendTcp(tunOut, serverIp, clientIp, serverPort, clientPort,
                            session.mySeq, session.clientSeq, 0x18,  // PSH+ACK
                            rbuf.copyOf(n))
                    session.mySeq += n
                }
            } catch (e: Exception) { /* socket closed */ }
            // Send FIN to client
            sendTcp(tunOut, serverIp, clientIp, serverPort, clientPort,
                    session.mySeq, session.clientSeq, 0x11)
            tcpSessions.remove(key)?.close()
        }
    }

    // ── Packet builders ───────────────────────────────────────────────────────

    private fun sendTcp(
        tunOut: FileOutputStream,
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        data: ByteArray = ByteArray(0)
    ) {
        val tcpLen = 20 + data.size
        val ipLen  = 20 + tcpLen
        val pkt    = ByteArray(ipLen)
        val bb     = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header
        bb.put(0x45.toByte()); bb.put(0)
        bb.putShort(ipLen.toShort())
        bb.putShort(0); bb.putShort(0x4000.toShort())
        bb.put(64); bb.put(6); bb.putShort(0)  // ttl, proto=TCP, checksum=0
        bb.put(srcIp); bb.put(dstIp)
        val ipCsum = checksum(pkt, 0, 20)
        pkt[10] = (ipCsum shr 8).toByte(); pkt[11] = ipCsum.toByte()

        // TCP header
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putInt(seq.toInt())
        bb.putInt(ack.toInt())
        bb.put(0x50.toByte())       // data offset = 5 (20 bytes)
        bb.put(flags.toByte())
        bb.putShort(65535.toShort())  // window
        bb.putShort(0)              // TCP checksum placeholder
        bb.putShort(0)              // urgent pointer
        if (data.isNotEmpty()) bb.put(data)

        // TCP checksum over pseudo-header + TCP segment
        val pseudo = byteArrayOf(
            srcIp[0], srcIp[1], srcIp[2], srcIp[3],
            dstIp[0], dstIp[1], dstIp[2], dstIp[3],
            0, 6, (tcpLen shr 8).toByte(), tcpLen.toByte()
        )
        val tcpCsum = checksum(pseudo + pkt.copyOfRange(20, ipLen))
        pkt[36] = (tcpCsum shr 8).toByte(); pkt[37] = tcpCsum.toByte()

        synchronized(tunOut) {
            try { tunOut.write(pkt) } catch (e: Exception) { }
        }
    }

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payLen: Int
    ): ByteArray {
        val udpLen = 8 + payLen
        val ipLen  = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(17); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        b.putShort(srcPort.toShort())
        b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort())
        b.putShort(0)  // UDP checksum (0 = disabled, valid for IPv4)
        b.put(payload, 0, payLen)
        return b.array()
    }

    // ── Internet checksum (RFC 1071) ────────────────────────────────────────

    private fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var sum = 0L
        var i   = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i   += 2
        }
        if ((end - offset) % 2 != 0)
            sum += (data[end - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L)
            sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv().toInt()) and 0xFFFF
    }

    // ── Photon ──────────────────────────────────────────────────────────────────

    private fun parsePhoton(payload: ByteArray) {
        try { PhotonParser.parse(payload).forEach { dispatcher.dispatch(it) } }
        catch (e: Exception) { Log.v(TAG, "photon: ${e.message}") }
    }

    // ── Extension helpers ─────────────────────────────────────────────────────

    private fun ByteArray.uShort(off: Int) =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun ByteArray.uInt32(off: Int): Long =
        ((this[off].toLong()   and 0xFF) shl 24) or
        ((this[off+1].toLong() and 0xFF) shl 16) or
        ((this[off+2].toLong() and 0xFF) shl 8)  or
        (this[off+3].toLong()  and 0xFF)

    private fun ByteArray.ip() =
        "${this[0].toInt() and 0xFF}.${this[1].toInt() and 0xFF}." +
        "${this[2].toInt() and 0xFF}.${this[3].toInt() and 0xFF}"

    // ── Notifications ────────────────────────────────────────────────────────

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

    // ── Inner data classes ─────────────────────────────────────────────────────

    private inner class UdpFlow(
        val socket:   DatagramSocket,
        val srcIp:    ByteArray,
        @Volatile var lastUsed: Long = System.currentTimeMillis(),
        var receiveJob: Job? = null
    ) {
        fun close() { receiveJob?.cancel(); runCatching { socket.close() } }
    }

    private inner class TcpSession(
        val socket:    Socket,
        val serverOut: java.io.OutputStream,
        @Volatile var clientSeq:   Long,
        @Volatile var mySeq:       Long,
        @Volatile var established: Boolean = true,
        var receiveJob: Job? = null
    ) {
        fun close() {
            established = false
            receiveJob?.cancel()
            runCatching { serverOut.close() }
            runCatching { socket.close() }
        }
    }
}
