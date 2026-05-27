package top.nkbe.npatch.manager

import android.os.Environment
import android.util.Log
import top.nkbe.npatch.config.Configs
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ManagerLogger {

    private const val TAG = "NPatch-ManagerLog"
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile private var logcatProcess: Process? = null
    @Volatile private var logcatThread: Thread? = null
    @Volatile private var fileOut: FileOutputStream? = null
    @Volatile private var currentDate: String = ""

    fun init() {
        if (Configs.outputFullLog) start()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    @Synchronized
    private fun start() {
        if (logcatProcess != null) return
        try {
            val pid = android.os.Process.myPid()
            val process = ProcessBuilder(
                "logcat", "-v", "threadtime", "--pid=$pid", "*:W"
            ).redirectErrorStream(true).start()
            logcatProcess = process

            val thread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line = reader.readLine()
                        while (line != null && !Thread.currentThread().isInterrupted) {
                            writeLine(line)
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            thread.isDaemon = true
            thread.name = "NPatch-LogcatReader"
            thread.start()
            logcatThread = thread
            Log.i(TAG, "ManagerLogger started for pid=$pid")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ManagerLogger", e)
        }
    }

    @Synchronized
    fun stop() {
        logcatProcess?.destroy()
        logcatProcess = null
        logcatThread?.interrupt()
        logcatThread = null
        runCatching { fileOut?.close() }
        fileOut = null
        currentDate = ""
    }

    private fun writeLine(line: String) {
        synchronized(this) {
            try {
                val today = dateFormat.format(Date())
                if (fileOut == null || today != currentDate) {
                    fileOut?.close()
                    val dir = File(
                        Environment.getExternalStorageDirectory(),
                        "Android/media/top.nkbe.npatch/log"
                    )
                    dir.mkdirs()
                    fileOut = FileOutputStream(File(dir, "$today-manager.log"), true)
                    currentDate = today
                }
                fileOut?.write((line + "\n").toByteArray())
                fileOut?.flush()
            } catch (_: Exception) {
            }
        }
    }
}
