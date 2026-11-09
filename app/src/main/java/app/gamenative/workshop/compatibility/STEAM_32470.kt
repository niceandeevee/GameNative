package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource

/**
 * STAR WARS Empire at War: Gold Pack (Steam)
 *
 * The game has no in-game mod list: a mod is loaded with MODPATH=<folder> on the command line (what
 * Steam's launch options are used for), and Forces of Corruption looks for local mods in
 * corruption\Mods. Under the real Steam client, items GameNative downloaded aren't known to Steam, so
 * STEAMMOD=<id> can't find them. Each enabled item is linked as corruption\Mods\<id> (to its folder
 * that holds Data\) and passed as MODPATH=Mods\<id>, the standard local-mod form; the first attempt
 * used ..\..\..\workshop\content\32470\<id>, which depends on what the game resolves it against.
 * Forces of Corruption loads one mod at a time, so the first enabled item is used. The launchers
 * (runme2 -> swfoc -> StarWarsG) don't reliably pass the argument on, so the gamecache DLL inside
 * StarWarsG.exe also appends it (GAMECACHE_ADD_ARGS) when it's missing. Nothing else is linked into
 * the game folder (METADATA_ONLY).
 */
val STEAM_WorkshopOverride_32470 = KeyedWorkshopCompatibilityOverride(
    gameSource = GameSource.STEAM,
    gameId = "32470",
    override = WorkshopCompatibilityOverride(
        exposureMode = WorkshopExposureMode.METADATA_ONLY,
        linkModsInto = "corruption/Mods",
        launchArgs = { ids -> "MODPATH=Mods\\${ids.first()}" },
    ),
)
