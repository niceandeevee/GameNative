package app.gamenative.gamefixes

/** Input compatibility choices that must be active while a game is running. */
object GameInputCompatibility {
    private val mouseDragCompatibilityGames = setOf(
        "STEAM_3858650", // Crushed in Time
        // Call of Duty: Black Ops (Zombies / Multiplayer). Mouselook from a stick bound to mouse
        // movement was jerky: without XGE, Wine's XInput2 can't receive raw motion, so it rebuilds
        // the camera delta from absolute pointer positions and its own cursor warps. With XGE the
        // exact deltas reach it as XI_RawMotion events.
        "STEAM_42700",
        "STEAM_42710",
    )

    /**
     * Enables narrowly scoped XGE support and sub-pixel mouse-drag accumulation
     * for games whose Windows pointer state diverges from the visible X11 cursor.
     */
    fun needsMouseDragCompatibility(appId: String, useGlibcContainer: Boolean): Boolean =
        !useGlibcContainer && appId in mouseDragCompatibilityGames
}
