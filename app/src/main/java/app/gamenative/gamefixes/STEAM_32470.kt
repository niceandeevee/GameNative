package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * STAR WARS Empire at War: Gold Pack (Steam)
 *
 * - Faster loading (gamecache, a pass-through powrprof.dll placed next to each StarWarsG.exe and
 *   loaded native for it; source in tools/gamecache64). StarWarsG.exe imports POWRPROF.dll itself, so
 *   Wine finds this copy in the game folder before any game code runs. (Earlier attempts as
 *   version.dll and psapi.dll never loaded: only Steam's tier0_s64.dll imports those, and Wine
 *   resolves its imports next to tier0 and in system32. Both are pinned to builtin so the old copies
 *   left in the game folder stay unused.) The Wine log shows "gamecache: command line: ..." with the
 *   arguments the game got (MODPATH for a Workshop mod). A file trace of a launch showed two costs, each a
 *   trip through Wine and FEX:
 *   - Before reading any model from its .meg archives, the game probes for it as a loose file under
 *     corruption\Data\Art\Models and GameData\Data\Art\Models, folders a Steam install doesn't have
 *     (4,001 of 4,094 opens in one stretch failed with "path not found"). The DLL remembers which
 *     folders under the install folder are missing and fails those opens at once with the same error.
 *     Missing folders are re-checked every 10 s, and creating a folder clears the cache.
 *   - It reads the archive indexes 64 bytes at a time, each after a seek. Read-only .meg opens are
 *     served from 128 KB blocks by the same self-checking cache as Black Ops' .iwd files.
 * - No LucasArts or Petroglyph intro: before playing a movie the game looks for it as a loose file under
 *   DATA\ART\MOVIES\BINKED and only then reads Movies.meg, so the same DLL answers that open for
 *   the intro logos with a zero-byte file and there is nothing to play. Every movie the game opens
 *   is logged ("gamecache: movie <name>"), so other intros can be added to its list.
 *   The game is 64-bit, so the hooks go through the import tables of the game's own modules (in
 *   arm64ec Wine, kernel32 is ARM64 code). The read cache only turns on when every file-position call
 *   those modules import can be hooked. The Wine log shows "gamecache: hooks installed (...)" at start
 *   and a summary at exit. The fix puts the files back on each launch; removing it means dropping
 *   these lines.
 * - No Steam overlay (gameoverlayrenderer disabled for runme2.exe, swfoc.exe and StarWarsG.exe): Steam
 *   injects it into all three and it hooks the game's Direct3D presents. Its UI process
 *   (gameoverlayui64) crashed during the loading screen on every logged launch, and Steam's error
 *   reporter then wrote and uploaded a crash dump twice while the game loaded (CPU at 88-93%).
 *   It can't be opened from a touch screen anyway. Removing it means dropping the three overrides.
 */
val STEAM_Fix_32470: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "32470",
    fixes = listOf(
        WineAppDefaultsFix(
            "StarWarsG.exe",
            "DllOverrides",
            mapOf(
                "powrprof" to "native,builtin",
                "psapi" to "builtin",
                "version" to "builtin",
                "gameoverlayrenderer64" to "",
            ),
        ),
        WineAppDefaultsFix("swfoc.exe", "DllOverrides", mapOf("gameoverlayrenderer64" to "")),
        WineAppDefaultsFix("runme2.exe", "DllOverrides", mapOf("gameoverlayrenderer" to "")),
        // Forces of Corruption and the original campaign each run their own StarWarsG.exe.
        GameDirAssetFix("gamefixes/gamecache64/powrprof.dll", "corruption/powrprof.dll"),
        GameDirAssetFix("gamefixes/gamecache64/powrprof.dll", "GameData/powrprof.dll"),
    ),
)
