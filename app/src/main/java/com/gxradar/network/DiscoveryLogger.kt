package com.gxradar.network

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DiscoveryLogger
 *
 * Two log files:
 *   unknown_entities.log  — Plan B: unmatched entity typeIds
 *   debug.log             — Full event dump for diagnostics (share via app)
 */
class DiscoveryLogger(context: Context) {

    companion object {
        private const val TAG          = "DiscoveryLogger"
        private const val UNKNOWN_LOG  = "unknown_entities.log"
        private const val DEBUG_LOG    = "debug.log"
        private const val MAX_SIZE_MB  = 5L
        private const val DEBUG_MAX_MB = 2L
    }

    private val dir         = context.getExternalFilesDir(null) ?: context.filesDir
    private val unknownFile = File(dir, UNKNOWN_LOG)
    private val debugFile   = File(dir, DEBUG_LOG)
    private val sdf         = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        dir.mkdirs()
        if (!unknownFile.exists()) unknownFile.writeText("# GX Radar unknown entities\n# timestamp|eventCode|typeId|uniqueName|posX|posZ\n")
        // Fresh debug log every app start
        debugFile.writeText("# GX Radar Debug Log — ${Date()}\n")
        Log.i(TAG, "Debug log: ${debugFile.absolutePath}")
    }

    /** Log an entity the DB could not resolve (Plan B). */
    fun logUnknownEntity(eventCode: Int, typeId: Int, uniqueName: String?, posX: Float, posZ: Float) {
        rotateIfNeeded(unknownFile, MAX_SIZE_MB)
        val line = "${sdf.format(Date())}|$eventCode|$typeId|${uniqueName ?: ""}|$posX|$posZ\n"
        appendLine(unknownFile, line)
    }

    /** Write a debug line (any string). Called by EventDispatcher in debug mode. */
    fun writeDebug(line: String) {
        rotateIfNeeded(debugFile, DEBUG_MAX_MB)
        appendLine(debugFile, "${sdf.format(Date())} $line\n")
    }

    fun getDebugFile(): File  = debugFile
    fun getUnknownFile(): File = unknownFile
    fun getDebugSizeKb(): Long = debugFile.length() / 1024

    private fun appendLine(file: File, line: String) {
        try {
            BufferedWriter(FileWriter(file, true)).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }

    private fun rotateIfNeeded(file: File, maxMb: Long) {
        if (file.length() > maxMb * 1_048_576L) {
            file.renameTo(File(dir, "${file.name}.old"))
            file.writeText("# rotated\n")
        }
    }
}
