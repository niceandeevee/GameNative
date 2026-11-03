package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Call of Duty: Black Ops II (Steam)
 *
 * Same engine generation and the same failure mode as Call of Duty: Black Ops (see STEAM_42700):
 * a Steam CEG protected, 32-bit D3D9 title that sits on a black screen with the defaults.
 *
 * - Steam CEG protected: the emulated Steam client / Steamless never get past a black screen, so
 *   default to the real Steam client in headless mode, which regenerates the exe on first launch.
 * - 32-bit D3D9 under WoW64: use DXVK, report 1 GB of VRAM and make the process large-address-aware.
 * - X11 display server: the gamescope session lets a 32-bit game's Vulkan bypass the wrapper ICD
 *   and map memory above 4 GB; the X11 path goes through the wrapper, which places mappings correctly.
 * - ALSA audio, builtin DirectSound and WMA decoder, native XAudio: the native dsound.dll fails in
 *   DllMain on a fresh container (the game aborts before opening a window), winepulse triggers a
 *   2.7 GB allocation a 32-bit process can't satisfy, and Wine's WMA path deadlocks or silences audio.
 * - No 3D for WineD3D in t6sp.exe and no Steam overlay in it (overlay injection hooks every
 *   graphics API with throwaway devices and its UI process crashes).
 *
 * Unlike Black Ops the game has controller support, so the virtual gamepad and the controller
 * profile are left at their defaults.
 */
val STEAM_Fix_202970: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "202970",
    fixes = listOf(
        SteamClientRequiredFix(),
        DxWrapperDefaultFix("dxvk", mapOf("maxDeviceMemory" to "1024")),
        WineEnvVarFix(mapOf("WINE_LARGE_ADDRESS_AWARE" to "1")),
        ContainerSettingDefaultFix.displayServer(Container.DISPLAY_SERVER_X11),
        ContainerSettingDefaultFix.audioDriver("alsa"),
        ContainerSettingDefaultFix.winComponent("wmdecoder", "0"),
        ContainerSettingDefaultFix.winComponent("directsound", "0"),
        ContainerSettingDefaultFix.winComponent("xaudio", "1"),
        WineAppDefaultsFix("t6sp.exe", "Direct3D", mapOf("renderer" to "no3d")),
        WineAppDefaultsFix("t6sp.exe", "DllOverrides", mapOf("gameoverlayrenderer" to "")),
    ),
)
