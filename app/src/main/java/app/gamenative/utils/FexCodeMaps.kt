package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FEX code maps: the list of guest blocks FEX compiled for one process, written with
 * FEX_ENABLECODECACHINGWIP=1 to AppData/Local/fex-emu/codemap/new/ in the prefix. They are the input
 * to the offline code cache compiler ([FexCodeCaches]) and do not depend on the FEX build that wrote
 * them. Recorded only when the container's FEXCore pack ships the offline compiler and code caches are
 * on. Adapted from upstream GameNative (#2358, #2416, #2517) with the upload left out: the maps never
 * leave the device.
 */
object FexCodeMaps {

    private const val CODEMAP_DIR = ".wine/drive_c/users/xuser/AppData/Local/fex-emu/codemap/new"
    internal const val ENTRY_BYTES = 12
    private const val MAX_MAP_BYTES = 8L * 1024 * 1024

    // Reserved file id that introduces a record instead of a block, and the record kinds.
    internal const val RESERVED_FILE_ID = -1L
    internal const val LOAD_EXTERNAL_LIBRARY = -1
    private const val SET_EXECUTABLE_32 = -18
    private const val SET_EXECUTABLE_64 = -17
    internal const val INDIRECT_BRANCH = -35
    internal const val PAIR_BYTES = 2 * ENTRY_BYTES

    /**
     * A FEXCore pack that can write usable maps and load caches ships its offline compiler next to the
     * DLLs (GameNative series gn-6 and later). Checking for that file is the capability test; no
     * version string is parsed.
     */
    @JvmStatic
    fun packSupportsCodeCaches(installDir: File?): Boolean =
        installDir != null && File(installDir, "system32/FEXOfflineCompiler64.exe").isFile

    @JvmStatic
    fun supportsCodeMaps(context: Context, container: Container): Boolean =
        supportsCodeMaps(context, ContentsManager(context), container)

    /** Same check with a caller's [ContentsManager], which has already scanned the installed packs. */
    @JvmStatic
    fun supportsCodeMaps(context: Context, contentsManager: ContentsManager, container: Container): Boolean {
        if (!"fexcore".equals(container.emulator, ignoreCase = true)) return false
        val profile = contentsManager.getProfileByEntryName("fexcore-${container.fexCoreVersion}") ?: return false
        return packSupportsCodeCaches(ContentsManager.getInstallDir(context, profile))
    }

    /** Whether this launch should have FEX write code maps: code caches are on and the pack supports them. */
    @JvmStatic
    fun captureEnabled(context: Context, container: Container): Boolean =
        PrefManager.fexCodeCacheEnabled && supportsCodeMaps(context, container)

    @JvmStatic
    fun captureEnabled(context: Context, contentsManager: ContentsManager, container: Container): Boolean =
        PrefManager.fexCodeCacheEnabled && supportsCodeMaps(context, contentsManager, container)

    fun codeMapDir(container: Container): File = File(container.rootDir, CODEMAP_DIR)

    /**
     * Whether FEX records indirect branch targets. Off: upstream turns them on only once its server
     * accepts them, and they change the cache's build id without helping the on-device compile.
     */
    @JvmStatic
    fun indirectBranchesEnabled(): Boolean = false

    data class MapInfo(
        val mainExe: String,
        val mainFileId: String,
        val bitness: Int,
        val entries: Int,
        val libraries: Int,
        val indirectBranches: Int = 0,
    )

    /**
     * Walks the map (FEXCore CodeMap): 12-byte entries {file id u64, block offset u32}, little
     * endian. The reserved file id (all ones) introduces a record instead of a block: offset
     * 0xffffffff = library load (u64 file id, NUL-terminated path padded to 4 bytes), offset
     * 0xffffffee / 0xffffffef = the main executable's file id for a 32-bit / 64-bit process,
     * offset 0xffffffdd = indirect branch: {file id, offset} of the jump or call, then of its target.
     * The file name is "<exe>-<fileid>.<time>.bin" (CodeMap::GetBaseFilename).
     */
    fun readInfo(file: File): MapInfo? {
        if (file.length() < 2L * ENTRY_BYTES || file.length() > MAX_MAP_BYTES) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var blocks = 0
        var libraries = 0
        var indirectBranches = 0
        var mainFileId: Long? = null
        var bitness = 0
        while (buf.remaining() >= ENTRY_BYTES) {
            val fileId = buf.long
            val blockOffset = buf.int
            if (fileId != -1L) {
                blocks++
                continue
            }
            when (blockOffset) {
                -1 -> { // 0xffffffff: library load
                    if (buf.remaining() < 8) return null
                    buf.long
                    var end = buf.position()
                    while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
                    if (end >= buf.limit()) return null
                    val next = (end + 1 + 3) and 3.inv()
                    if (next > buf.limit()) return null // padding cut off: not a complete record
                    buf.position(next)
                    libraries++
                }
                -18, -17 -> { // 0xffffffee / 0xffffffef: main executable
                    if (buf.remaining() < 8) return null
                    mainFileId = buf.long
                    bitness = if (blockOffset == -18) 32 else 64
                }
                INDIRECT_BRANCH -> {
                    if (buf.remaining() < PAIR_BYTES) return null
                    buf.position(buf.position() + PAIR_BYTES)
                    indirectBranches++
                }
                else -> return null
            }
        }
        if (buf.hasRemaining() || mainFileId == null || (blocks == 0 && indirectBranches == 0)) return null
        val base = file.name.removeSuffix(".bin").substringBeforeLast('.')
        val exe = base.substringBeforeLast('-').ifEmpty { base }
        return MapInfo(exe, "%016x".format(mainFileId), bitness, blocks, libraries, indirectBranches)
    }

    /** Walks a map and hands each entry or record to [visit] with its raw bytes. */
    internal fun forEachRecord(bytes: ByteArray, visit: (fileId: Long, blockOffset: Int, raw: ByteArray) -> Unit) {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= ENTRY_BYTES) {
            val start = buf.position()
            val fileId = buf.long
            val blockOffset = buf.int
            if (fileId == RESERVED_FILE_ID) {
                when (blockOffset) {
                    LOAD_EXTERNAL_LIBRARY -> {
                        if (buf.remaining() < 8) return
                        buf.long
                        var end = buf.position()
                        while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
                        if (end >= buf.limit()) return
                        val next = (end + 1 + 3) and 3.inv()
                        if (next > buf.limit()) return
                        buf.position(next)
                    }
                    SET_EXECUTABLE_32, SET_EXECUTABLE_64 -> {
                        if (buf.remaining() < 8) return
                        buf.long
                    }
                    INDIRECT_BRANCH -> {
                        if (buf.remaining() < PAIR_BYTES) return
                        buf.position(buf.position() + PAIR_BYTES)
                    }
                    else -> return
                }
            }
            visit(fileId, blockOffset, bytes.copyOfRange(start, buf.position()))
        }
    }
}
