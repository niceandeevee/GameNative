package app.gamenative.gamefixes

/** Input compatibility choices that must be active while a game is running. */
object GameInputCompatibility {
    private val mouseDragCompatibilityGames = setOf(
        "STEAM_3858650", // Crushed in Time
    )

    // Games whose mouselook warps the cursor back to the window centre every frame and reads the
    // offset (no raw input, no DirectInput mouse). Going through the X server, each stick-to-mouse
    // step races the warp and reaches Wine in bursts, so turning is jerky; injected as Win32
    // relative input (SendInput inside Wine) the steps land in the game's own cursor state.
    private val win32RelativeMouseGames = setOf(
        "STEAM_42700", // Call of Duty: Black Ops (campaign / Zombies)
        "STEAM_42710", // Call of Duty: Black Ops - Multiplayer
    )

    /**
     * Enables narrowly scoped XGE support and sub-pixel mouse-drag accumulation
     * for games whose Windows pointer state diverges from the visible X11 cursor.
     */
    fun needsMouseDragCompatibility(appId: String, useGlibcContainer: Boolean): Boolean =
        !useGlibcContainer && appId in mouseDragCompatibilityGames

    /** Whether injected mouse movement should always go to Wine as relative Win32 input. */
    fun needsWin32RelativeMouse(appId: String): Boolean = appId in win32RelativeMouseGames
}
