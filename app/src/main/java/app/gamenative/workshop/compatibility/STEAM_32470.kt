package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource

/**
 * STAR WARS Empire at War: Gold Pack (Steam)
 *
 * The game has no in-game mod list: a mod is loaded with MODPATH=<folder> on the command line (what
 * Steam's launch options are used for). Under the real Steam client, items GameNative downloaded
 * aren't known to Steam, so STEAMMOD=<id> can't find them; MODPATH points straight at the folder,
 * relative to the game's corruption\ folder (steamapps\common\<game>\corruption ->
 * steamapps\workshop\content\32470\<id>). Forces of Corruption loads one mod at a time, so the first
 * enabled item is used.
 */
val STEAM_WorkshopOverride_32470 = KeyedWorkshopCompatibilityOverride(
    gameSource = GameSource.STEAM,
    gameId = "32470",
    override = WorkshopCompatibilityOverride(
        launchArgs = { ids -> "MODPATH=..\\..\\..\\workshop\\content\\32470\\${ids.first()}" },
    ),
)
