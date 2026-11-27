package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * STAR WARS Galactic Battlegrounds Saga (Steam)
 *
 * - cnc-ddraw as the DirectDraw wrapper. battlegrounds_x1.exe is a 2D DirectDraw game (Genie engine, 8-bit
 *   palette surfaces). The DirectX 1-8 default left it on Wine's own ddraw (wined3d over OpenGL), where the touch
 *   screen's mouse did nothing in game. The game opens an exclusive fullscreen surface at its own resolution,
 *   while the app's X screen and touch mapping keep the container's size. cnc-ddraw's profile for this exe
 *   (ddraw.ini, [battlegrounds_x1]: nonexclusive, adjmouse) stretches the game to the window and scales the
 *   mouse to match, which is how this game is normally run on modern Windows too.
 *   Set once per container, so a different wrapper chosen afterwards is kept.
 */
val STEAM_Fix_356500: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "356500",
    fixes = listOf(
        DxWrapperDefaultFix("cnc-ddraw"),
    ),
)
