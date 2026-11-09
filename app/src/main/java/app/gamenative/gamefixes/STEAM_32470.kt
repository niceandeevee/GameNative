package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * STAR WARS Empire at War: Gold Pack (Steam)
 *
 * - Faster loading (gamecache, a pass-through version.dll placed next to each StarWarsG.exe and loaded
 *   native for it; source in tools/gamecache64). A file trace of a launch showed two costs, each a
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
 */
val STEAM_Fix_32470: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "32470",
    fixes = listOf(
        WineAppDefaultsFix("StarWarsG.exe", "DllOverrides", mapOf("version" to "native,builtin")),
        // Forces of Corruption and the original campaign each run their own StarWarsG.exe.
        GameDirAssetFix("gamefixes/gamecache64/version.dll", "corruption/version.dll"),
        GameDirAssetFix("gamefixes/gamecache64/version.dll", "GameData/version.dll"),
    ),
)
