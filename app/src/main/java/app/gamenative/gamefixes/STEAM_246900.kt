package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.container.Container

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
 * - No Steam overlay in the game (gameoverlayrenderer disabled for UDK.exe and RUSTClientProcess.exe, as for
 *   Black Ops). Steam injects it into UDK.exe, where it hooks D3D9/D3D11/DXGI; in the sessions that froze, its
 *   64-bit UI process (gameoverlayui64.exe) started, Steam's error reporter followed a second later, and a game
 *   thread then sat on the process heap's lock, held by no thread ("heap.cs ... blocked by 0000"), until
 *   the game died. The overlay can't be opened from a touch screen anyway.
 * - X11 display server, as for the other 32-bit Direct3D games (Black Ops, STEAM_42700). Under the gamescope
 *   session a 32-bit game's Vulkan skips the wrapper ICD, so its memory mappings land above 4 GB, out of
 *   the process's reach. A gamescope launch showed RKSetup's window but never UDK.exe's: UDK.exe stopped
 *   after its first Vulkan probe (DXGI adapter query) and went 6 s without loading anything, where an X11
 *   launch moves on to its device in under a second and maps its window about 4.5 s after starting.
 *   Set once per container, so a later choice of gamescope in the container settings is kept.
 */
val STEAM_Fix_246900: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "246900",
    fixes = listOf(
        ContainerSettingDefaultFix.displayServer(Container.DISPLAY_SERVER_X11),
        WineAppDefaultsFix("UDK.exe", "DllOverrides", mapOf("gameoverlayrenderer" to "")),
        WineAppDefaultsFix("RUSTClientProcess.exe", "DllOverrides", mapOf("gameoverlayrenderer" to "")),
        WineEnvVarFix(
            mapOf(
                "FEX_TSOENABLED" to "1",
                "FEX_HALFBARRIERTSOENABLED" to "1",
                "FEX_DISKCACHE" to "1",
            ),
        ),
    ),
)
