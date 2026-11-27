package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ── readFirstLine ─────────────────────────────────────────────────────────

    @Test
    fun `readFirstLine returns null when file does not exist`() {
        val missing = tmp.root.resolve("missing.txt")
        assertNull(FileUtils.readFirstLine(missing))
    }

    @Test
    fun `readFirstLine returns null when file is empty`() {
        val file = tmp.newFile("empty.txt")
        assertNull(FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine returns the single line`() {
        val file = tmp.newFile("single.txt").also { it.writeText("hello") }
        assertEquals("hello", FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine returns only the first line of a multi-line file`() {
        val file = tmp.newFile("multi.txt").also { it.writeText("first\nsecond\nthird") }
        assertEquals("first", FileUtils.readFirstLine(file))
    }

    @Test
    fun `readFirstLine preserves leading and trailing whitespace`() {
        val file = tmp.newFile("padded.txt").also { it.writeText("  42  \nsecond") }
        assertEquals("  42  ", FileUtils.readFirstLine(file))
    }

    @Test
    fun `delete removes a tree with a read-only directory in it`() {
        val root = tmp.newFolder("container")
        val locked = File(root, "drive_c/locked").apply { mkdirs() }
        File(locked, "save.dat").writeText("x")
        File(root, "after/later.txt").apply { parentFile!!.mkdirs() }.writeText("y")
        locked.setWritable(false, false)

        assertTrue(FileUtils.delete(root))
        assertFalse(root.exists())
    }

    @Test
    fun `delete unlinks a symlinked directory without emptying its target`() {
        val target = tmp.newFolder("game").also { File(it, "keep.bin").writeText("z") }
        val root = tmp.newFolder("prefix")
        Files.createSymbolicLink(File(root, "a:").toPath(), target.toPath())

        assertTrue(FileUtils.delete(root))
        assertFalse(root.exists())
        assertTrue(File(target, "keep.bin").exists())
    }
}
