package app.gamenative.gamefixes

/** Input compatibility choices that must be active while a game is running. */
object GameInputCompatibility {
    private val mouseDragCompatibilityGames = setOf(
        "STEAM_3858650", // Crushed in Time
    )

    // Games that need injected mouse movement sent as Win32 relative input (SendInput inside Wine)
    // whenever their own cursor is hidden. Black Ops was tried and got no mouse input at all this
    // way (menus and mouselook), so nothing is listed.
    private val win32RelativeMouseGames = emptySet<String>()

    /**
     * Enables narrowly scoped XGE support and sub-pixel mouse-drag accumulation
     * for games whose Windows pointer state diverges from the visible X11 cursor.
     */
    fun needsMouseDragCompatibility(appId: String, useGlibcContainer: Boolean): Boolean =
        !useGlibcContainer && appId in mouseDragCompatibilityGames

    /** Whether injected mouse movement should always go to Wine as relative Win32 input. */
    fun needsWin32RelativeMouse(appId: String): Boolean = appId in win32RelativeMouseGames
}
