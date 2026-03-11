package com.gxradar.network.photon

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses a raw UDP payload into zero or more PhotonMessages.
 *
 * ═══ UDP Payload layout ═══════════════════════════════════════
 *  [12-byte Photon header] [command₁] [command₂] … [commandN]
 *
 *  Header (12 bytes, all Big-Endian):
 *    0-1  PeerID
 *    2    Flags      (bit1 = encrypted — we skip encrypted packets)
 *    3    CommandCount
 *    4-7  Timestamp
 *    8-11 Challenge
 *
 *  Command header (12 bytes):
 *    0    CommandType  (6=SendReliable  7=SendUnreliable)
 *    1    ChannelID
 *    2    CommandFlags
 *    3    Reserved
 *    4-7  CommandLength  (total length including this 12-byte header)
 *    8-11 ReliableSeqNum
 *    [type==7: +4 bytes UnreliableSeqNum]
 *
 *  Message envelope (inside command payload):
 *    0    0xF3  magic
 *    1    MessageType  (0x02=Request  0x03=Response  0x04=Event)
 *    2+   message-specific fields
 *
 *  Event (0x04):
 *    0    standardEventCode (ignored for Albion — use params[252])
 *    then parameterTable
 *
 *  Request (0x02):
 *    0    opCode
 *    then parameterTable
 *
 *  Response (0x03):
 *    0    opCode
 *    1-2  returnCode (BE short)
 *    ?    debugMessage (typed value — read & discard)
 *    then parameterTable
 * ══════════════════════════════════════════════════════════════
 */
object PhotonParser {

    private const val MAGIC         = 0xF3.toByte()
    private const val MSG_REQUEST   = 0x02.toByte()
    private const val MSG_RESPONSE  = 0x03.toByte()
    private const val MSG_EVENT     = 0x04.toByte()
    private const val FLAG_ENCRYPTED = 0x02
    private const val CMD_RELIABLE   = 6
    private const val CMD_UNRELIABLE = 7

    fun parse(payload: ByteArray): List<PhotonMessage> {
        if (payload.size < 12) return emptyList()
        val results = mutableListOf<PhotonMessage>()

        try {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            buf.position(2)
            val flags = buf.get().toInt() and 0xFF
            if (flags and FLAG_ENCRYPTED != 0) return emptyList() // encrypted = login, skip

            val commandCount = buf.get().toInt() and 0xFF
            buf.position(12) // skip Timestamp(4) + Challenge(4)

            repeat(commandCount) {
                if (buf.remaining() < 12) return@repeat
                val cmdStart = buf.position()

                val cmdType  = buf.get().toInt() and 0xFF
                buf.get()  // ChannelID
                buf.get()  // CommandFlags
                buf.get()  // Reserved
                val cmdLength = buf.int   // total length including this header
                buf.int                   // ReliableSeqNum

                // Advance past unreliable seq if needed
                if (cmdType == CMD_UNRELIABLE) buf.int

                // Only parse reliable/unreliable send commands
                if (cmdType != CMD_RELIABLE && cmdType != CMD_UNRELIABLE) {
                    safeJump(buf, cmdStart + cmdLength)
                    return@repeat
                }

                val remaining = cmdStart + cmdLength - buf.position()
                if (remaining < 2) {
                    safeJump(buf, cmdStart + cmdLength)
                    return@repeat
                }

                // Message envelope
                val magic = buf.get()
                if (magic != MAGIC) {
                    safeJump(buf, cmdStart + cmdLength)
                    return@repeat
                }

                val msgType = buf.get()
                val msg = try {
                    when (msgType) {
                        MSG_EVENT    -> parseEvent(buf)
                        MSG_REQUEST  -> parseRequest(buf)
                        MSG_RESPONSE -> parseResponse(buf)
                        else         -> null
                    }
                } catch (e: Exception) {
                    null // malformed sub-message — skip gracefully
                }

                msg?.let { results.add(it) }
                safeJump(buf, cmdStart + cmdLength)
            }
        } catch (e: Exception) {
            // Malformed outer packet — return what we managed to parse
        }

        return results
    }

    // ── Message parsers ───────────────────────────────────────────────────────

    private fun parseEvent(buf: ByteBuffer): PhotonMessage.Event {
        val standardCode = buf.get().toInt() and 0xFF   // Photon's own event code
        val params = Protocol16Reader.readParameterTable(buf)

        // Albion puts its real event code in params[252], not the standard code.
        val eventCode = when (val raw = params[252]) {
            is Byte  -> raw.toInt() and 0xFF
            is Short -> raw.toInt() and 0xFFFF
            is Int   -> raw
            else     -> standardCode  // fallback (e.g. event code 2 omits params[252])
        }
        return PhotonMessage.Event(eventCode, params)
    }

    private fun parseRequest(buf: ByteBuffer): PhotonMessage.OperationRequest {
        val opCode = buf.get().toInt() and 0xFF
        val params = Protocol16Reader.readParameterTable(buf)
        return PhotonMessage.OperationRequest(opCode, params)
    }

    private fun parseResponse(buf: ByteBuffer): PhotonMessage.OperationResponse {
        val opCode     = buf.get().toInt() and 0xFF
        val returnCode = buf.short
        Protocol16Reader.readValue(buf)   // debug message — typed value, read & discard
        val params = Protocol16Reader.readParameterTable(buf)
        return PhotonMessage.OperationResponse(opCode, returnCode, params)
    }

    private fun safeJump(buf: ByteBuffer, target: Int) {
        if (target > buf.limit()) return
        if (target > buf.position()) buf.position(target)
    }
}
