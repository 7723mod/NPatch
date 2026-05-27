package top.nkbe.npatch.manager

import android.os.Environment
import android.util.Log
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.lspApp
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ManagerLogger {

    private const val TAG = "NPatch-ManagerLog"
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var out: FileOutputStream? = null
    @Volatile private var currentDate: String = ""

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write("W", tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        write("E", tag, msg, tr)
    }

    fun patchLog(level: Int, msg: String) {
        val levelChar = when (level) {
            android.util.Log.DEBUG -> "D"
            android.util.Log.ERROR -> "E"
            else -> "I"
        }
        write(levelChar, "Patcher", msg, null)
    }

    fun isEnabled() = Configs.outputFullLog

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        if (!Configs.outputFullLog) return
        synchronized(this) {
            try {
                val today = dateFormat.format(Date())
                if (out == null || today != currentDate) {
                    out?.close()
                    val dir = File(
                        Environment.getExternalStorageDirectory(),
                        "Android/media/top.nkbe.npatch/log"
                    )
                    dir.mkdirs()
                    out = FileOutputStream(File(dir, "$today-manager.log"), true)
                    currentDate = today
                }
                val time = timeFormat.format(Date())
                val line = buildString {
                    append(time).append(' ')
                    append('[').append(level).append("] ")
                    append(tag).append(": ")
                    append(msg)
                    if (tr != null) {
                        append('\n').append(Log.getStackTraceString(tr))
                    }
                    append('\n')
                }
                out?.write(line.toByteArray())
                out?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log", e)
            }
        }
    }

    fun closeAndReset() {
        synchronized(this) {
            out?.close()
            out = null
            currentDate = ""
        }
    }
}
