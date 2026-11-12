package app.gamenative.utils

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnrealCrashLogTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory(prefix = "unreal-crash-log").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun crashExcerpt_startsAtTheFirstCrashLine() {
        val text = listOf(
            "Log: Loading map",
            "Critical: appError called:",
            "Critical: Assertion failed: Object",
            "Critical: Windows GetLastError: (0)",
        ).joinToString("\r\n")

        val excerpt = UnrealCrashLog.crashExcerpt(text)!!

        assertTrue(excerpt.startsWith("Critical: appError called:"))
        assertTrue(excerpt.contains("Assertion failed"))
        assertFalse(excerpt.contains("Loading map"))
    }

    @Test
    fun crashExcerpt_isNullForACleanLog() {
        assertNull(UnrealCrashLog.crashExcerpt("Log: Loading map\nExit: Exiting."))
    }

    @Test
    fun logRecentCrash_readsUtf16LogsWrittenDuringTheSession() {
        val logs = File(dir, "UDKGame/Logs").apply { mkdirs() }
        val log = File(logs, "Launch.log")
        log.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Log: start\r\nCritical: appError called:\r\n".toByteArray(Charsets.UTF_16LE))

        assertTrue(UnrealCrashLog.logRecentCrash(dir, log.lastModified()))
        assertFalse(UnrealCrashLog.logRecentCrash(dir, log.lastModified() + 60_000))
        assertEquals(1, logs.listFiles()!!.size)
    }
}
