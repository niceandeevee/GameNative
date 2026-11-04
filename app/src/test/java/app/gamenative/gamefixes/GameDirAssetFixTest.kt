package app.gamenative.gamefixes

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDirAssetFixTest {
    @Test
    fun writesOnceAndReplacesChangedCopies() {
        val dir = Files.createTempDirectory("gamedir").toFile()
        val target = File(dir, "xinput1_3.dll")
        val bytes = byteArrayOf(0x4D, 0x5A, 1, 2, 3)

        assertTrue(GameDirAssetFix.place(target, bytes))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(GameDirAssetFix.place(target, bytes))

        target.writeBytes(byteArrayOf(9, 9))
        assertTrue(GameDirAssetFix.place(target, bytes))
        assertArrayEquals(bytes, target.readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(dir, "xinput1_3.dll.orig").readBytes())
        assertFalse(File(dir, "xinput1_3.dll.tmp").exists())
        dir.deleteRecursively()
    }
}
