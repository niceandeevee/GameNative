package app.gamenative.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DotNetBitnessTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory(prefix = "dotnet-bitness").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun require32Bit_setsTheFlagOnAnyCpuImagesOnce() {
        val exe = File(dir, "UnSetup.exe").apply { writeBytes(peImage(clrFlags = 0x1 or 0x20000)) }

        assertTrue(DotNetBitness.require32Bit(exe))
        assertEquals(0x1 or 0x2, clrFlags(exe.readBytes()))
        assertFalse(DotNetBitness.require32Bit(exe))
    }

    @Test
    fun require32Bit_leavesOtherImagesAlone() {
        val mixedMode = File(dir, "mixed.exe").apply { writeBytes(peImage(clrFlags = 0x0)) }
        val native = File(dir, "native.exe").apply { writeBytes(peImage(clrFlags = null)) }
        val pe64 = File(dir, "x64.exe").apply { writeBytes(peImage(clrFlags = 0x1, magic = 0x20b)) }
        val junk = File(dir, "junk.exe").apply { writeBytes(ByteArray(16)) }
        val before = listOf(mixedMode, native, pe64, junk).map { it.readBytes() }

        assertFalse(DotNetBitness.require32Bit(mixedMode))
        assertFalse(DotNetBitness.require32Bit(native))
        assertFalse(DotNetBitness.require32Bit(pe64))
        assertFalse(DotNetBitness.require32Bit(junk))
        listOf(mixedMode, native, pe64, junk).zip(before).forEach { (file, bytes) -> assertTrue(file.readBytes().contentEquals(bytes)) }
    }

    @Test
    fun applyForLaunch_findsUnSetupWhateverTheCase() {
        val binaries = File(dir, "binaries").apply { mkdirs() }
        val exe = File(binaries, "unsetup.EXE").apply { writeBytes(peImage(clrFlags = 0x1)) }

        DotNetBitness.applyForLaunch(dir)

        assertEquals(0x3, clrFlags(exe.readBytes()))
    }

    private companion object {
        const val PE_OFFSET = 0x80
        const val SECTION_RVA = 0x2000
        const val SECTION_RAW = 0x400
        const val CLR_IN_SECTION = 0x8

        /** A PE image with one section; when [clrFlags] is set, the CLR header sits in that section. */
        fun peImage(clrFlags: Int?, magic: Int = 0x10b): ByteArray {
            val buffer = ByteBuffer.allocate(0x800).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(0, 0x5A4D)
            buffer.putInt(0x3C, PE_OFFSET)
            buffer.putInt(PE_OFFSET, 0x00004550)
            buffer.putShort(PE_OFFSET + 4, 0x14c)
            buffer.putShort(PE_OFFSET + 6, 1)
            val optionalSize = 96 + 16 * 8
            buffer.putShort(PE_OFFSET + 20, optionalSize.toShort())
            val optional = PE_OFFSET + 24
            buffer.putShort(optional, magic.toShort())
            buffer.putInt(optional + 92, 16)
            if (clrFlags != null) {
                buffer.putInt(optional + 96 + 14 * 8, SECTION_RVA + CLR_IN_SECTION)
                buffer.putInt(optional + 96 + 14 * 8 + 4, 72)
            }
            val section = optional + optionalSize
            ".text".toByteArray().forEachIndexed { i, b -> buffer.put(section + i, b) }
            buffer.putInt(section + 8, 0x200)
            buffer.putInt(section + 12, SECTION_RVA)
            buffer.putInt(section + 16, 0x200)
            buffer.putInt(section + 20, SECTION_RAW)
            if (clrFlags != null) {
                val clr = SECTION_RAW + CLR_IN_SECTION
                buffer.putInt(clr, 72)
                buffer.putInt(clr + 16, clrFlags)
            }
            return buffer.array()
        }

        fun clrFlags(bytes: ByteArray): Int =
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(SECTION_RAW + CLR_IN_SECTION + 16)
    }
}
