package app.gamenative.utils

import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * Copies an Unreal Engine crash report into the app log.
 *
 * Unreal Engine 3 games write their log to <Game>\Logs\Launch.log in the install folder, and when
 * they crash, their crash handler writes the error and the call stack there ("Critical: appError
 * called", "=== Critical error: ===", ...). The app log only shows that the game exited, so a crash
 * during play (Viscera Cleanup Detail froze and closed this way) can't be traced from it. After a
 * session, the crash part of a Launch.log written during the session is logged.
 */
object UnrealCrashLog {
    private const val MAX_TAIL_BYTES = 256 * 1024
    private const val LINES_AFTER = 60
    private const val MAX_DEPTH = 3
    private val CRASH_MARKER = Regex(
        "(?i)critical error|appError called|unhandled exception|crash in runnable thread|fatal error|assertion failed",
    )

    /** Logs the crash lines of every Launch.log under [gameDir] written since [sinceMillis]. */
    fun logRecentCrash(gameDir: File, sinceMillis: Long): Boolean {
        var found = false
        val logs = gameDir.walkTopDown().maxDepth(MAX_DEPTH)
            .filter { it.isFile && it.name.equals("Launch.log", ignoreCase = true) && it.lastModified() >= sinceMillis }
            .toList()
        for (log in logs) {
            val excerpt = runCatching { crashExcerpt(readTail(log)) }.getOrNull() ?: continue
            Timber.w("Unreal crash report in ${log.relativeTo(gameDir).path}:\n$excerpt")
            found = true
        }
        return found
    }

    /** The first crash line of [text] and up to [LINES_AFTER] lines after it, or null if none. */
    internal fun crashExcerpt(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { CRASH_MARKER.containsMatchIn(it) }
        if (start < 0) return null
        return lines.subList(start, minOf(lines.size, start + 1 + LINES_AFTER)).joinToString("\n").trimEnd()
    }

    private fun readTail(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = maxOf(0L, length - MAX_TAIL_BYTES)
            val bytes = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            // UE3 writes its log as UTF-16LE (with a BOM) or as ANSI depending on the build.
            val sample = bytes.take(256)
            val utf16 = sample.isNotEmpty() && sample.count { it.toInt() == 0 } > sample.size / 4
            val aligned = if (utf16 && start % 2 == 1L) bytes.copyOfRange(1, bytes.size) else bytes
            return String(aligned, if (utf16) Charsets.UTF_16LE else Charsets.ISO_8859_1)
        }
    }
}
