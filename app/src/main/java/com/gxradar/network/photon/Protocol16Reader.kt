package com.gxradar.network.photon

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Decodes every Protocol16 typed value from a ByteBuffer (Big-Endian by default).
 *
 * Type code reference:
 *   42='*' Null | 98='b' Byte | 111='o' Bool | 107='k' Short | 105='i' Int
 *   108='l' Long | 102='f' Float | 100='d' Double | 115='s' String
 *   120='x' ByteArray | 121='y' Array | 122='z' ObjectArray
 *   104='h' Hashtable | 68='D' Dictionary
 */
object Protocol16Reader {

    /**
     * Read one typed value. When called without a typeCode, reads the type byte first.
     */
    fun readValue(buf: ByteBuffer, typeCode: Byte = buf.get()): Any? {
        return when (typeCode.toInt() and 0xFF) {
            42  -> null                          // '*' Null
            98  -> buf.get()                     // 'b' Byte
            111 -> buf.get() != 0.toByte()       // 'o' Boolean
            107 -> buf.short                     // 'k' Short  (BE)
            105 -> buf.int                       // 'i' Integer (BE)
            108 -> buf.long                      // 'l' Long    (BE)
            102 -> buf.float                     // 'f' Float   (BE)
            100 -> buf.double                    // 'd' Double  (BE)
            115 -> readString(buf)               // 's' String
            120 -> readByteArray(buf)            // 'x' ByteArray
            121 -> readArray(buf)                // 'y' Array (homogeneous)
            122 -> readObjectArray(buf)          // 'z' ObjectArray (heterogeneous)
            104 -> readHashtable(buf)            // 'h' Hashtable
            68  -> readDictionary(buf)           // 'D' Dictionary
            else -> null                         // Unknown type — skip gracefully
        }
    }

    // ── Parameter table ───────────────────────────────────────────────────────

    /**
     * Reads the standard Photon parameter table:
     *   count (BE short) + count × [key(byte) + typeCode(byte) + value]
     */
    fun readParameterTable(buf: ByteBuffer): Map<Int, Any?> {
        val count = buf.short.toInt() and 0xFFFF
        val map = HashMap<Int, Any?>(count)
        repeat(count) {
            val key = buf.get().toInt() and 0xFF
            val value = readValue(buf)
            map[key] = value
        }
        return map
    }

    // ── Composite types ───────────────────────────────────────────────────────

    private fun readString(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        if (len == 0) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readByteArray(buf: ByteBuffer): ByteArray {
        val len = buf.int
        if (len <= 0) return ByteArray(0)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return bytes
    }

    private fun readArray(buf: ByteBuffer): Array<Any?> {
        val size = buf.short.toInt() and 0xFFFF
        val elementType = buf.get()               // all elements share one type code
        return Array(size) { readValue(buf, elementType) }
    }

    private fun readObjectArray(buf: ByteBuffer): Array<Any?> {
        val size = buf.short.toInt() and 0xFFFF
        return Array(size) { readValue(buf) }     // each element has its own type code
    }

    private fun readHashtable(buf: ByteBuffer): Map<Any?, Any?> {
        val size = buf.short.toInt() and 0xFFFF
        val map = HashMap<Any?, Any?>(size)
        repeat(size) {
            val key   = readValue(buf)
            val value = readValue(buf)
            map[key] = value
        }
        return map
    }

    private fun readDictionary(buf: ByteBuffer): Map<Any?, Any?> {
        val keyType = buf.get()
        val valType = buf.get()
        val size    = buf.short.toInt() and 0xFFFF
        val map = HashMap<Any?, Any?>(size)
        repeat(size) {
            val key   = if (keyType == 0.toByte()) readValue(buf) else readValue(buf, keyType)
            val value = if (valType == 0.toByte()) readValue(buf) else readValue(buf, valType)
            map[key] = value
        }
        return map
    }
}
