package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import java.io.File
import java.nio.charset.StandardCharsets
import timber.log.Timber

/**
 * Raises numeric dvars in id Tech 3 style config files (`seta name "value"`, as written by the Call
 * of Duty games) to at least a floor, on every launch. Values already at or above the floor, and
 * dvars the file does not set, are left alone, so lower settings the player picks are kept.
 * Missing files are skipped: the game writes them on its first run.
 */
class CfgDvarFloorFix(
    private val relativePaths: List<String>,
    private val floors: Map<String, Int>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        var changed = false
        for (relativePath in relativePaths) {
            val file = resolveIgnoringCase(File(installPath), relativePath) ?: continue
            runCatching {
                val original = file.readText(StandardCharsets.UTF_8)
                val updated = raiseDvars(original, floors)
                if (updated != original) {
                    file.writeText(updated, StandardCharsets.UTF_8)
                    changed = true
                    Timber.tag("GameFixes").i("Raised ${floors.keys} in $relativePath for game $gameId")
                }
            }.onFailure { Timber.tag("GameFixes").w(it, "Failed to update $relativePath for game $gameId") }
        }
        return changed
    }

    companion object {
        /** Windows games don't care about case, so their folders may be "Players" or "players". */
        internal fun resolveIgnoringCase(root: File, relativePath: String): File? {
            var current = root
            for (part in relativePath.split('/').filter { it.isNotEmpty() }) {
                val exact = File(current, part)
                current = if (exact.exists()) exact
                else current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
            return current.takeIf { it.isFile }
        }

        internal fun raiseDvars(content: String, floors: Map<String, Int>): String {
            var updated = content
            for ((name, floor) in floors) {
                val regex = Regex("(?im)^(\\s*(?:seta|set)\\s+${Regex.escape(name)}\\s+\"?)(-?\\d+)(\"?)")
                updated = regex.replace(updated) { match ->
                    val value = match.groupValues[2].toIntOrNull() ?: return@replace match.value
                    if (value >= floor) match.value
                    else match.groupValues[1] + floor + match.groupValues[3]
                }
            }
            return updated
        }
    }
}
