package com.winlator.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DXVKHelperTest {
    @Test
    fun configFileHasOneOptionPerLine() {
        assertEquals(
            "dxgi.maxDeviceMemory = 1024\nd3d9.maxAvailableMemory = 1024\n",
            DXVKHelper.toConfigFileContent("dxgi.maxDeviceMemory = 1024; d3d9.maxAvailableMemory = 1024"),
        )
    }

    @Test
    fun winePathGoesThroughRootDrive() {
        assertEquals("Z:/data/x/.config/dxvk.conf", DXVKHelper.toWinePath(File("/data/x/.config/dxvk.conf")))
    }

    @Test
    fun writesOptionsAndRemovesFileWhenNoneLeft() {
        val dir = Files.createTempDirectory("dxvk").toFile()
        val file = File(dir, ".config/dxvk.conf")

        DXVKHelper.writeConfigFile(file, "d3d9.maxAvailableMemory = 1024")
        assertEquals("d3d9.maxAvailableMemory = 1024\n", file.readText())

        DXVKHelper.writeConfigFile(file, "")
        assertFalse(file.exists())
        dir.deleteRecursively()
    }
}
