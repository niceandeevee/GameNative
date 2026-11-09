package app.gamenative

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A local running crash handler.
 * Any uncaught exceptions will be saved located locally in a text file, aka: Crash Report.
 * File location: /<user storage>/Android/data/app.gamenative/files/crash_logs/
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val LOG_CAT_COUNT = 256
        private const val CRASH_FILE_HISTORY_COUNT = 1

        val timestamp: String
            get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

        /**
         * Logcat command
         */
        private fun logcatCommand(count: Int): String = "logcat -d -t $count --pid=${android.os.Process.myPid()}"

        fun initialize(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = CrashHandler(context.applicationContext, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        }

        /**
         * Helper method to get logcat info live
         */
        fun getAppLogs(lineCount: Int = LOG_CAT_COUNT): String {
            var process: Process? = null
            var reader: BufferedReader? = null

            return try {
                process = Runtime.getRuntime().exec(logcatCommand(lineCount))
                reader = BufferedReader(InputStreamReader(process.inputStream))

                val log = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    log.append(line).append("\n")
                }

                log.toString()
            } catch (e: Exception) {
                "Failed to capture logs: ${e.message}"
            } finally {
                reader?.close()
                process?.destroy()
            }
        }
    }

    private val crashFileDir by lazy {
        File(context.getExternalFilesDir(null), "crash_logs").apply {
            if (!exists()) mkdirs()
        }
    }

    private val recentLogcat: String
        get() = try {
            val process = Runtime.getRuntime().exec("logcat -d -t $LOG_CAT_COUNT --pid=${android.os.Process.myPid()}")
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Failed to retrieve logcat: ${e.message}"
        }

    /**
     * Also puts the report in the public Download/GameNative folder, so it can be sent even when the
     * app crashes before Settings > Debug can be opened (Android/data isn't reachable in file managers).
     */
    private fun copyToDownloads(name: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/GameNative",
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {
            // Best effort: the copy in crash_logs is already written.
        }
    }

    private val cleanupOldCrashFiles: () -> Unit = {
        crashFileDir.listFiles()?.let { files ->
            if (files.size > CRASH_FILE_HISTORY_COUNT) {
                files.sortByDescending { it.lastModified() }
                files.drop(CRASH_FILE_HISTORY_COUNT).forEach { it.delete() }
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        PrefManager.recentlyCrashed = true

        saveCrashToFile(throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashToFile(throwable: Throwable) {
        try {
            val stackTrace = StringWriter().apply {
                val pw = PrintWriter(this)
                throwable.printStackTrace(pw)
            }.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

            val crashReport = buildString {
                appendLine("Timestamp: $timestamp")
                appendLine("App Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
                appendLine()
                appendLine("---------- Device Information ----------")
                appendLine("${android.os.Build.MANUFACTURER} - ${android.os.Build.BRAND} - ${android.os.Build.MODEL}")
                appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
                appendLine()
                appendLine("---------- Cause ----------")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("---------- Stack Trace ----------")
                appendLine(stackTrace)
                appendLine()
                appendLine("---------- Logcat ----------")
                appendLine(recentLogcat)
            }

            File(crashFileDir, "pluvia_crash_$timestamp.txt").writeText(crashReport)

            cleanupOldCrashFiles()
            copyToDownloads("pluvia_crash_$timestamp.txt", crashReport)
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(Thread.currentThread(), throwable)
        }
    }
}
