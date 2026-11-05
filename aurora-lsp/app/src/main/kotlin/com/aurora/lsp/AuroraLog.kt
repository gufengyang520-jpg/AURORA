package com.aurora.lsp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling log the user never opens. It rides along with a feedback report so "it does not work"
 * arrives with evidence instead of a guess.
 *
 * Capped in size and trimmed on write, so a phone that runs this for a year still has a small file.
 */
object AuroraLog {

    private const val FILE_NAME = "aurora.log"
    private const val MAX_BYTES = 192 * 1024
    private const val KEEP_BYTES = 96 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.length() > MAX_BYTES) trim(file)
                file.appendText(stamp.format(Date()) + " [" + tag + "] " + message + "\n")
            } catch (t: Throwable) {
                // A log that throws is worse than no log.
            }
        }
    }

    fun dump(context: Context): String = synchronized(lock) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else ""
        } catch (t: Throwable) {
            ""
        }
    }

    private fun trim(file: File) {
        val text = file.readText()
        if (text.length <= KEEP_BYTES) return
        val start = text.length - KEEP_BYTES
        val nextLine = text.indexOf('\n', start)
        file.writeText(if (nextLine >= 0) text.substring(nextLine + 1) else text.substring(start))
    }
}