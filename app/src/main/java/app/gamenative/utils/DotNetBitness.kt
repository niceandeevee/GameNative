package app.gamenative.utils

import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * Makes "Any CPU" .NET programs run as 32-bit processes.
 *
 * Wine starts an IL-only .NET executable that doesn't ask for 32-bit as a 64-bit process. On the
 * ARM64EC builds Wine Mono can't be loaded into a 64-bit process ("Could not load Mono into this
 * process"), so the program never starts, while Mono loads fine in 32-bit processes. Setting the CLR
 * header's 32BITREQUIRED flag (what `corflags /32BITREQ+` does) makes Wine start it under WoW64.
 *
 * Unreal Engine 3 games built with the UDK need this: on first run UDK.exe starts
 * Binaries\UnSetup.exe to show and record the UDK EULA, and quits if it can't (Viscera Cleanup
 * Detail closed a few seconds after starting, every launch). Steam's verify restores the original
 * file; it is patched again at the next launch.
 */
object DotNetBitness {
    private const val IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR = 14
    private const val PE32_MAGIC = 0x10b
    private const val COMIMAGE_FLAGS_ILONLY = 0x1
    private const val COMIMAGE_FLAGS_32BITREQUIRED = 0x2
    private const val COMIMAGE_FLAGS_32BITPREFERRED = 0x20000

    /** UE3 UDK's EULA/setup tool, relative to the game directory. */
    private val UE3_SETUP_TOOLS = listOf("Binaries/UnSetup.exe")

    fun applyForLaunch(gameDir: File) {
        for (relative in UE3_SETUP_TOOLS) {
            val exe = resolveCaseInsensitive(gameDir, relative) ?: continue
            try {
                if (require32Bit(exe)) Timber.i("Set ${exe.name} to run as a 32-bit process (Wine Mono loads only there)")
            } catch (e: Exception) {
                Timber.w(e, "Could not set ${exe.absolutePath} to 32-bit")
            }
        }
    }

    /**
     * Sets 32BITREQUIRED on an IL-only PE32 .NET image. Returns true when the file was changed; false
     * when it isn't a .NET PE32 image, isn't IL-only, or already runs as 32-bit.
     */
    internal fun require32Bit(file: File): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val flagsOffset = clrFlagsOffset(raf) ?: return false
            raf.seek(flagsOffset)
            val flags = readInt(raf)
            if (flags and COMIMAGE_FLAGS_ILONLY == 0) return false
            if (flags and COMIMAGE_FLAGS_32BITREQUIRED != 0) return false
            val patched = (flags or COMIMAGE_FLAGS_32BITREQUIRED) and COMIMAGE_FLAGS_32BITPREFERRED.inv()
            raf.seek(flagsOffset)
            writeInt(raf, patched)
            return true
        }
    }

    /** File offset of the CLR header's Flags field, or null if this isn't a .NET PE32 image. */
    private fun clrFlagsOffset(raf: RandomAccessFile): Long? {
        if (raf.length() < 0x40) return null
        raf.seek(0)
        if (readShort(raf) != 0x5A4D) return null // "MZ"
        raf.seek(0x3C)
        val peOffset = readInt(raf).toLong()
        if (peOffset <= 0 || peOffset + 24 > raf.length()) return null
        raf.seek(peOffset)
        if (readInt(raf) != 0x00004550) return null // "PE\0\0"
        raf.seek(peOffset + 6)
        val sections = readShort(raf)
        raf.seek(peOffset + 20)
        val optionalSize = readShort(raf)
        val optional = peOffset + 24
        raf.seek(optional)
        if (readShort(raf) != PE32_MAGIC) return null
        raf.seek(optional + 92)
        val directories = readInt(raf)
        if (directories <= IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR) return null
        raf.seek(optional + 96 + IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR * 8L)
        val clrRva = readInt(raf).toLong() and 0xFFFFFFFFL
        val clrSize = readInt(raf).toLong() and 0xFFFFFFFFL
        if (clrRva == 0L || clrSize < 20) return null

        var section = optional + optionalSize
        repeat(sections) {
            raf.seek(section + 8)
            val virtualSize = readInt(raf).toLong() and 0xFFFFFFFFL
            val virtualAddress = readInt(raf).toLong() and 0xFFFFFFFFL
            val rawSize = readInt(raf).toLong() and 0xFFFFFFFFL
            val rawPointer = readInt(raf).toLong() and 0xFFFFFFFFL
            val span = maxOf(virtualSize, rawSize)
            if (clrRva >= virtualAddress && clrRva + 20 <= virtualAddress + span) {
                val headerOffset = rawPointer + (clrRva - virtualAddress)
                return (headerOffset + 16).takeIf { it + 4 <= raf.length() }
            }
            section += 40
        }
        return null
    }

    private fun readShort(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        return b0 or (b1 shl 8)
    }

    private fun readInt(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun writeInt(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun resolveCaseInsensitive(root: File, relativePath: String): File? {
        var current = root
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        return current.takeIf { it.isFile }
    }
}
