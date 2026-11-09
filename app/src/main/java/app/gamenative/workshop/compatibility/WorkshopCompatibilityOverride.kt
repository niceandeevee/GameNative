package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource

/**
 * Describes a per-game Workshop compatibility override.
 *
 * The default behavior is intentionally represented by the absence of an
 * override in [WorkshopCompatibilityRegistry].
 */
data class WorkshopCompatibilityOverride(
    val exposureMode: WorkshopExposureMode? = null,
    /**
     * Command-line arguments that make the game load downloaded Workshop items, for games that take
     * mods on the command line (no in-game list) and run under the real Steam client, which doesn't
     * know about items GameNative downloaded. Gets the enabled, fully downloaded item ids (sorted).
     */
    val launchArgs: ((itemIds: List<Long>) -> String)? = null,
    val ignoreManualModPath: Boolean = false,
    val cleanupNestedSteamSettingsArtifacts: Boolean = false,
)

enum class WorkshopExposureMode {
    /**
     * Expose Workshop items through Steam metadata only. GameNative still
     * writes mods.json, but skips filesystem Workshop links inside the game.
     */
    METADATA_ONLY,
}

data class KeyedWorkshopCompatibilityOverride(
    val gameSource: GameSource,
    val gameId: String,
    val override: WorkshopCompatibilityOverride,
)
