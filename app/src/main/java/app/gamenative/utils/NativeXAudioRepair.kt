package app.gamenative.utils

import android.content.Context
import app.gamenative.gamefixes.LibarchiveCabEntryExtractor
import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * Makes the "xaudio" Windows component take effect when it is set to native.
 *
 * Games that play xWMA through XAudio2 (Call of Duty: Black Ops: footsteps, gunshots, zombies,
 * melee) are silent with Wine's xaudio2, which hands xWMA to winegstreamer and fails. The native
 * component fixes that only if Microsoft's DLLs really end up in the prefix, which a failed or
 * partial component install leaves unchecked. This verifies them every launch and, when one is
 * missing or is still Wine's builtin, pulls it out of any DirectX June 2010 cabinet on disk
 * (the game's own redist or Steamworks Shared). Every result goes to [LOG_NAME].
 */
object NativeXAudioRepair {
    const val LOG_NAME = "launch_setup.log"

    /** The XAudio 2.7 era DLLs, by the June 2010 cabinet that carries them. */
    private val CABINET_DLLS = linkedMapOf(
        "xaudio" to listOf("xaudio2_7.dll"),
        "x3daudio" to listOf("x3daudio1_7.dll"),
        "xapofx" to listOf("xapofx1_5.dll"),
        "xact" to listOf("xactengine3_7.dll"),
    )
    private val ALL_DLLS = CABINET_DLLS.values.flatten()

    private const val WINE_BUILTIN_MARKER = "Wine builtin DLL"
    private const val MAX_SEARCH_DEPTH = 6

    fun logFile(context: Context): File = File(context.getExternalFilesDir(null), "wine_logs/$LOG_NAME")

    /** Starts this launch's setup log. */
    fun beginLog(context: Context, appId: String) {
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.writeText("Launch setup for $appId at ${java.util.Date()}\n")
        }
    }

    fun log(context: Context, line: String) {
        Timber.tag("LaunchSetup").i(line)
        runCatching { logFile(context).appendText(line + "\n") }
    }

    /**
     * Checks the native XAudio DLLs in [windowsDir] and repairs missing ones from DirectX
     * cabinets under [searchRoots]. Returns true when the 32-bit xaudio2_7.dll is native.
     */
    fun ensure(context: Context, windowsDir: File, searchRoots: List<File>): Boolean {
        val wow64 = File(windowsDir, "syswow64")
        val sys32 = File(windowsDir, "system32")
        log(context, "XAudio native check in ${windowsDir.absolutePath}")
        var missing = ALL_DLLS.flatMap { dll ->
            listOf("x86" to File(wow64, dll), "x64" to File(sys32, dll))
        }.filter { (arch, file) ->
            val status = status(file)
            log(context, "  $arch ${file.name}: $status")
            status != Status.NATIVE
        }
        if (missing.isNotEmpty()) {
            val cabinets = findCabinets(searchRoots)
            log(context, "  DirectX cabinets found: ${cabinets.size} under ${searchRoots.joinToString { it.absolutePath }}")
            missing = missing.filterNot { (arch, file) -> repair(context, arch, file, cabinets) }
        }
        val x86Ok = status(File(wow64, "xaudio2_7.dll")) == Status.NATIVE
        log(context, if (x86Ok) "  32-bit xaudio2_7.dll is native" else "  32-bit xaudio2_7.dll is NOT native: game audio will use Wine's xaudio2")
        return x86Ok
    }

    private enum class Status { MISSING, WINE_BUILTIN, NATIVE }

    private fun status(file: File): Status {
        if (!file.isFile || file.length() < 512) return Status.MISSING
        return if (isWineBuiltin(file)) Status.WINE_BUILTIN else Status.NATIVE
    }

    /** Wine's PE builtins carry "Wine builtin DLL" right after the DOS header. */
    internal fun isWineBuiltin(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(0x80)
            raf.readFully(head)
            String(head, Charsets.ISO_8859_1).contains(WINE_BUILTIN_MARKER)
        }
    }.getOrDefault(false)

    private fun findCabinets(roots: List<File>): List<File> = roots
        .filter { it.isDirectory }
        .flatMap { root ->
            root.walkTopDown().maxDepth(MAX_SEARCH_DEPTH)
                .filter { it.isFile && it.extension.equals("cab", ignoreCase = true) }
                .filter { cab ->
                    val name = cab.name.lowercase()
                    CABINET_DLLS.keys.any { name.contains(it) } && (name.contains("x86") || name.contains("x64"))
                }
                .toList()
        }
        .distinctBy { it.canonicalPath }
        // Newest redist first: June 2010 is what ships the 2.7 DLLs
        .sortedByDescending { it.name.lowercase().startsWith("jun2010") }

    private fun repair(context: Context, arch: String, target: File, cabinets: List<File>): Boolean {
        val family = CABINET_DLLS.entries.first { target.name in it.value }.key
        // A missing entry just returns false, so try every cabinet of this architecture, the
        // one named for the DLL's family first (cabinet contents vary between redist releases).
        val candidates = cabinets
            .filter { it.name.lowercase().contains(arch) }
            .sortedByDescending { it.name.lowercase().contains(family) }
        for (cab in candidates) {
            val partial = File(target.parentFile, target.name + ".part")
            try {
                target.parentFile?.mkdirs()
                if (LibarchiveCabEntryExtractor.extract(cab, target.name, partial) && status(partial) == Status.NATIVE) {
                    if (target.exists()) target.delete()
                    if (partial.renameTo(target)) {
                        log(context, "  repaired $arch ${target.name} from ${cab.name}")
                        return true
                    }
                }
            } catch (e: Exception) {
                log(context, "  ${cab.name}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                partial.delete()
            }
        }
        log(context, "  could not repair $arch ${target.name} (${candidates.size} $arch cabinets tried)")
        return false
    }
}
