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
 * Discovery Logger — Plan B
 *
 * Every entity spawn that has no DB match is written here immediately.
 * After a play session, pull the log with:
 *
 *   adb pull /sdcard/Android/data/com.gxradar.debug/files/unknown_entities.log
 *
 * The typeId → uniqueName pairs it captures seed the Step 3 SQLite DB.
 *
 * Log format: timestamp|eventCode|typeId|uniqueName|posX|posZ
 */
class DiscoveryLogger(context: Context) {

    companion object {
        private const val TAG           = "DiscoveryLogger"
        private const val LOG_FILENAME  = "unknown_entities.log"
        private const val MAX_SIZE_MB   = 10L
        private const val HEADER        =
            "# GX Radar — Discovery Log\n# timestamp|eventCode|typeId|uniqueName|posX|posZ\n"
    }

    private val logFile: File = File(
        context.getExternalFilesDir(null),
        LOG_FILENAME
    )

    // Writer shared across calls — opened/closed per write to avoid leaks
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        logFile.parentFile?.mkdirs()
        if (!logFile.exists()) logFile.writeText(HEADER)
        Log.i(TAG, "Discovery log → ${logFile.absolutePath}")
    }

    /**
     * Log an entity that could not be resolved from the local DB.
     * Thread-safe: appends synchronously (capture thread only).
     */
    fun logUnknownEntity(
        eventCode: Int,
        typeId: Int,
        uniqueName: String?,
        posX: Float,
        posZ: Float
    ) {
        rotatIfNeeded()
        val line = buildString {
            append(sdf.format(Date()))
            append('|').append(eventCode)
            append('|').append(typeId)
            append('|').append(uniqueName ?: "")
            append('|').append(posX)
            append('|').append(posZ)
            append('\n')
        }
        try {
            BufferedWriter(FileWriter(logFile, true)).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }

    fun getLogPath(): String = logFile.absolutePath

    fun getLogSizeKb(): Long = logFile.length() / 1024

    private fun rotatIfNeeded() {
        if (logFile.length() > MAX_SIZE_MB * 1_048_576L) {
            logFile.renameTo(File(logFile.parent, "$LOG_FILENAME.old"))
            logFile.writeText(HEADER + "# (rotated — previous log saved as .old)\n")
        }
    }
}
