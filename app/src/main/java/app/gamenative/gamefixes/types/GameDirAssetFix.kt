package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Places a file bundled in the app's assets into the game's install folder on every launch, writing
 * it only when it is missing or differs. Used for small helper DLLs that sit next to the game exe
 * (the game's own files are never modified; deleting the placed file undoes the fix).
 */
class GameDirAssetFix(
    private val assetPath: String,
    private val gameRelativePath: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = runCatching {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        place(File(installPath, gameRelativePath), bytes).also { written ->
            if (written) Timber.tag("GameFixes").i("Placed $assetPath as $gameRelativePath for game $gameId")
        }
    }.getOrElse { error ->
        Timber.tag("GameFixes").w(error, "Failed to place $assetPath for game $gameId")
        false
    }

    companion object {
        /** Writes [bytes] to [target] unless it already holds them. Returns whether it wrote. */
        internal fun place(target: File, bytes: ByteArray): Boolean {
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
                return false
            }
            target.parentFile?.mkdirs()
            // A file the game shipped under this name is kept once as <name>.orig.
            val backup = File(target.parentFile, target.name + ".orig")
            if (target.isFile && !backup.exists()) target.copyTo(backup)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    error("could not move ${temp.name} to ${target.name}")
                }
            }
            return true
        }
    }
}
