package app.gamenative.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The map reader the on-device code cache compile uses (adapted from upstream GameNative's tests). */
class FexCodeMapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Written by FEX 2611-gn-6 on a Retroid Pocket 6 for Steam's loader: 318 blocks, 4 libraries.
    private val fixtureName = "steamclient_loader_x64.exe-74ec33596a84c560.134384505043741006.bin"

    // Written by FEX 2611.1-gn9 with indirect branch recording on, cut to 300 blocks and 700 indirect branches.
    private val indirectFixtureName = "castlevania_demo.exe-dc4dfc928a9853a9.134401664583785092.bin"

    private fun resource(name: String): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fexcodemaps/$name")!!.use { it.readBytes() }
        return tmp.newFile(name).apply { writeBytes(bytes) }
    }

    @Test
    fun `a pack supports caches when it ships the offline compiler`() {
        val pack = tmp.newFolder("2611-gn-6")
        assertFalse(FexCodeMaps.packSupportsCodeCaches(pack))
        assertFalse(FexCodeMaps.packSupportsCodeCaches(null))
        File(pack, "system32").mkdirs()
        File(pack, "system32/FEXOfflineCompiler64.exe").writeBytes(ByteArray(16))
        assertTrue(FexCodeMaps.packSupportsCodeCaches(pack))
    }

    @Test
    fun `reads a real map written by FEX`() {
        val info = FexCodeMaps.readInfo(resource(fixtureName))!!
        assertEquals("steamclient_loader_x64.exe", info.mainExe)
        assertEquals("74ec33596a84c560", info.mainFileId)
        assertEquals(64, info.bitness)
        assertEquals(318, info.entries)
        assertEquals(4, info.libraries)
    }

    @Test
    fun `reads a real map with indirect branch records`() {
        val info = FexCodeMaps.readInfo(resource(indirectFixtureName))!!
        assertEquals("castlevania_demo.exe", info.mainExe)
        assertEquals(64, info.bitness)
        assertEquals(300, info.entries)
        assertEquals(33, info.libraries)
        assertEquals(700, info.indirectBranches)
    }

    @Test
    fun `rejects an empty or truncated map`() {
        assertNull(FexCodeMaps.readInfo(tmp.newFile("winhandler.exe-48ac2ba1b68866ae.1.bin")))
        val full = resource(fixtureName)
        val truncated = tmp.newFile("truncated.exe-74ec33596a84c560.2.bin")
        truncated.writeBytes(full.readBytes().copyOf(full.length().toInt() - 5))
        assertNull(FexCodeMaps.readInfo(truncated))
    }

    @Test
    fun `rejects a map without a main executable record`() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(1L).putInt(0x1000).putLong(1L).putInt(0x2000)
        val file = tmp.newFile("blocks.exe-0000000000000001.3.bin").apply { writeBytes(buf.array()) }
        assertNull(FexCodeMaps.readInfo(file))
    }

    @Test
    fun `every block and record of a real map is visited`() {
        val map = resource(fixtureName)
        var blocks = 0
        var records = 0
        FexCodeMaps.forEachRecord(map.readBytes()) { fileId, _, _ -> if (fileId == FexCodeMaps.RESERVED_FILE_ID) records++ else blocks++ }
        val info = FexCodeMaps.readInfo(map)!!
        assertEquals(info.entries, blocks)
        // Each library load and the main executable record.
        assertEquals(info.libraries + 1, records)
    }
}
