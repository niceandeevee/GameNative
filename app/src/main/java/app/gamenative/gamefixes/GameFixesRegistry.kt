package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.service.SteamService
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object GameFixesRegistry {
    private const val GAME_DRIVE_LETTER = "A"
    private const val FIXES_WINE_VERSION_EXTRA = "gameFixesWineVersion"
    private const val STEAM_CLIENT_REQUIRED_EXTRA = "steamClientRequiredFixApplied"

    /** Suffix of the marks kept by ContainerSettingDefaultFix, DxWrapperDefaultFix and ControlProfileDefaultFix. */
    private const val DEFAULT_FIX_APPLIED_SUFFIX = "DefaultFixApplied"

    private val fixes: Map<Pair<GameSource, String>, GameFix> = listOf(
        STEAM_Fix_220,
        STEAM_Fix_400,
        STEAM_Fix_22300,
        STEAM_Fix_22330,
        STEAM_Fix_22370,
        STEAM_Fix_22380,
        STEAM_Fix_22490,
        STEAM_Fix_42700,
        STEAM_Fix_42710,
        STEAM_Fix_202970,
        STEAM_Fix_202990,
        STEAM_Fix_212910,
        STEAM_Fix_312520,
        STEAM_Fix_413150,
        STEAM_Fix_413420,
        STEAM_Fix_595520,
        STEAM_Fix_658920,
        STEAM_Fix_752580,
        STEAM_Fix_1293830,
        STEAM_Fix_1637320,
        STEAM_Fix_1962700,
        STEAM_Fix_2868840,
        STEAM_Fix_3373660,
        STEAM_Fix_3602270,
        STEAM_Fix_4320000,
        STEAM_Fix_990080,
        CUSTOM_GAME_Fix_WowB_ARM64,
    ).associateBy { it.gameSource to it.gameId }

    private var fixesProvider: () -> Map<Pair<GameSource, String>, GameFix> = { fixes }

    fun applyFor(context: Context, appId: String, container: Container) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val catalogId = when (source) {
            // Custom game ids are a hash of the install folder, so key on the launch executable's file name.
            GameSource.CUSTOM_GAME -> {
                val exe = CustomGameScanner.getLaunchExecutable(container)
                if (exe.isEmpty()) return
                exe.substringAfterLast('\\').substringAfterLast('/').lowercase()
            }
            else -> gameId
        }
        Timber.i("GameFixesRegistry: Applying fixes for game: $source $catalogId if available")
        val fix = fixesProvider()[source to catalogId] ?: return
        reapplyAfterWineChange(container)
        val (installPath, installPathWindows) = resolvePaths(context, source, gameId, appId, container) ?: return
        fix.apply(context, catalogId, installPath, installPathWindows, container)
    }

    /**
     * Restores a game's fixed settings: forgets that its default fixes were applied and applies them
     * again now (when the game is installed; otherwise on its next launch). Used after the container
     * is reset to defaults, which would otherwise leave generic defaults the game is known to fail on.
     */
    fun resetToGameDefaults(context: Context, appId: String, container: Container) {
        clearAppliedMarks(container)
        container.saveData()
        Timber.i("GameFixesRegistry: Cleared applied fix marks for $appId")
        applyFor(context, appId, container)
    }

    /**
     * Default fixes apply once per container and then respect whatever the user sets. A Wine version
     * change rebuilds the prefix and is where settings drift into combinations that no longer start
     * the game, so a new Wine version applies the game's fixes again.
     */
    private fun reapplyAfterWineChange(container: Container) {
        val wine = container.wineVersion.orEmpty()
        val previous = container.getExtra(FIXES_WINE_VERSION_EXTRA).orEmpty()
        if (previous == wine) return
        if (previous.isNotEmpty()) {
            clearAppliedMarks(container)
            Timber.i("GameFixesRegistry: Wine changed from $previous to $wine, applying game fixes again")
        }
        container.putExtra(FIXES_WINE_VERSION_EXTRA, wine)
        container.saveData()
    }

    /** Removes the "already applied" marks every default-style fix keeps in the container extras. */
    internal fun clearAppliedMarks(container: Container) {
        val extras = container.extraData ?: return
        val keys = extras.keys().asSequence()
            .filter { it == STEAM_CLIENT_REQUIRED_EXTRA || it.endsWith(DEFAULT_FIX_APPLIED_SUFFIX) }
            .toList()
        keys.forEach { extras.remove(it) }
    }

    private fun resolvePaths(
        context: Context,
        source: GameSource,
        gameId: String,
        appId: String,
        container: Container,
    ): Pair<String, String>? {
        return when (source) {
            GameSource.STEAM -> {
                val path = SteamService.getAppDirPath(gameId.toInt())
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.CUSTOM_GAME -> {
                val path = CustomGameScanner.getFolderPathFromAppId(appId) ?: return null
                if (path.isEmpty() || !File(path).exists()) return null
                val letter = Container.drivesIterator(container.drives)
                    .firstOrNull { it[1] == path }?.get(0) ?: GAME_DRIVE_LETTER
                path to "$letter:\\"
            }
        }
    }

    /**
     * Test-only hook to override the game-fixes provider.
     * Not intended for production code paths.
     *
     * @param provider Fixes provider for tests; pass null to restore the default provider.
     */
    internal fun setFixesProviderForTests(provider: (() -> Map<Pair<GameSource, String>, GameFix>)?) {
        fixesProvider = provider ?: { fixes }
    }
}
