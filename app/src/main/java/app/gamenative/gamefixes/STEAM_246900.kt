package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Viscera Cleanup Detail (Steam)
 *
 * - x86 memory ordering kept on in FEX (TSO and half-barrier TSO), whatever FEX preset the container uses.
 *   The game is Unreal Engine 3 with PhysX, APEX cloth, audio and network threads running beside the
 *   game thread. With the EXTREME preset (TSO off) a session ran at ~58 fps for a minute and a half,
 *   then UDK.exe crashed in a worker thread (Unreal's crash handler, ReportFault, exit); before that,
 *   three of its threads had sat 60 s at a time on a critical section that Wine reported as held by
 *   no thread ("blocked by 0000"), the kind of state lost memory ordering between threads leaves.
 *   Vector and memcpy TSO stay off, as in the INTERMEDIATE preset (the app's default), to keep most of the speed.
 *   Set only when the user hasn't set these variables for the container themselves.
 * - FEX's disk cache of translated code, so later launches don't translate the same code again.
 */
val STEAM_Fix_246900: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "246900",
    fixes = listOf(
        WineEnvVarFix(
            mapOf(
                "FEX_TSOENABLED" to "1",
                "FEX_HALFBARRIERTSOENABLED" to "1",
                "FEX_DISKCACHE" to "1",
            ),
        ),
    ),
)
