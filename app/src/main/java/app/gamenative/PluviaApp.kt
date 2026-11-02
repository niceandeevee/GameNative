package app.gamenative

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StrictMode
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.display.GamescopeCommandQueue
import app.gamenative.events.EventDispatcher
import app.gamenative.mods.NexusAuthManager
import app.gamenative.powercontrol.PowerManager
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.xserver.RadialMenuCoordinator
import app.gamenative.utils.AppExitRecorder
import app.gamenative.utils.ContainerMigrator
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.downloader.ContainerFilesDownloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import android.app.Application

import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : Application() {


    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        preloadSystemLibraries()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        NetworkMonitor.init(this)

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Android's record of why the last process died (exported with Settings > Save logcat)
        appScope.launch {
            try {
                AppExitRecorder.record(applicationContext)
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: exit record failed")
            }
        }

        // Init our datastore preferences.
        PrefManager.init(this)
        NexusAuthManager.initialize(this)

        DownloadService.populateDownloadService(this)

        appScope.launch {
            ContainerMigrator.migrateLegacyContainersIfNeeded(
                context = applicationContext,
                onProgressUpdate = null,
                onComplete = null
            )
        }

        // Preload all container files in the background
        // not under Robolectric: every test boots a fresh app with a fresh filesDir, so this re-downloaded
        // every container archive per test. code that needs a file still fetches it on demand.
        if (Build.FINGERPRINT != "robolectric") {
            appScope.launch {
                ContainerFilesDownloader.preloadAllContainerFiles(applicationContext)
            }
        }

        // Clear any stale temporary config overrides from previous app sessions
        try {
            IntentLaunchManager.clearAllTemporaryOverrides()
            Timber.d("[PluviaApp]: Cleared temporary config overrides from previous session")
        } catch (e: Exception) {
            Timber.e(e, "[PluviaApp]: Failed to clear temporary config overrides")
        }

        Thread {
            PowerManager.initialize(this)
        }.start()
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        private lateinit var instance: PluviaApp

        /** The app's native library dir, or null before the application object exists. */
        internal fun nativeLibraryDir(): String? =
            if (::instance.isInitialized) instance.applicationInfo.nativeLibraryDir else null
        private var cachedDefaultScreenSize: String? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var radialMenuCoordinator: RadialMenuCoordinator? = null
        var achievementWatcher: app.gamenative.service.AchievementWatcher? = null
        var steamHostAchievementPoller: app.gamenative.service.SteamHostAchievementPoller? = null

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true
        var isImmersiveActivityResumed: Boolean = false
        // True while the booting splash covers the game screen (and its Resume overlay).
        @Volatile
        var isBootingSplashShowing: Boolean = false

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        fun shutdownEnvironment() {
            val env = xEnvironment
            Timber.i("shutdownEnvironment: env=%s", env != null)

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { achievementWatcher?.stop() }
                .onFailure { Timber.e(it, "shutdownEnvironment: achievementWatcher.stop") }
            runCatching { steamHostAchievementPoller?.stop() }
                .onFailure { Timber.e(it, "shutdownEnvironment: steamHostAchievementPoller.stop") }
            runCatching { SteamService.clearCachedAchievements() }
                .onFailure { Timber.e(it, "shutdownEnvironment: clearCachedAchievements") }
            runCatching { touchpadView?.releasePointerCapture() }
                .onFailure { Timber.e(it, "shutdownEnvironment: releasePointerCapture") }
            runCatching { radialMenuCoordinator?.detach() }
                .onFailure { Timber.e(it, "shutdownEnvironment: radialMenuCoordinator.detach") }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Timber.e(it, "shutdownEnvironment: stopEnvironmentComponents") }

            // Stop performance driver
            PowerManager.stop()

            xEnvironment = null
            inputControlsView = null
            inputControlsManager = null
            touchpadView = null
            radialMenuCoordinator = null
            achievementWatcher = null
            steamHostAchievementPoller = null
            ActiveGameRegistry.clear()
            SteamService.clearSuspendedGameState()
            SteamService.keepAlive = false
            SteamService.clearPlayingConflict()
            clearActiveSuspendState()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
            // Drop anything the gamescope queue still holds for the session that
            // is ending; it must not leak into the next one.
            GamescopeCommandQueue.reset()
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

        /** The default display's HDR capabilities, or null when this panel reports no HDR types. */
        @JvmStatic
        fun getDisplayHdrCapabilities(): Display.HdrCapabilities? {
            return try {
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                val caps = display?.hdrCapabilities
                val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    display?.mode?.supportedHdrTypes
                } else {
                    @Suppress("DEPRECATION")
                    caps?.supportedHdrTypes
                }
                if (caps == null || hdrTypes == null || hdrTypes.isEmpty()) null else caps
            } catch (e: Exception) {
                Timber.e(e, "Failed to read display HDR capabilities")
                null
            }
        }

        /**
         * The default display's real pixel size as "WxH" in landscape orientation (e.g. "2160x1080"),
         * or null when it can't be read yet.
         *
         * Gamescope-only: this is the scanout geometry for -W/-H and GNDRM_SCANOUT. It is
         * deliberately *not* [getDefaultScreenSize], which answers in aspect-ratio presets
         * (1280x720 / 1280x800 / 1280x960) meant to be a container's internal desktop resolution —
         * presenting at that preset makes gamescope render 720p and resample it onto the panel.
         */
        @JvmStatic
        fun getNativeDisplaySize(): String? {
            return try {
                if (!::instance.isInitialized) return null
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null

                val width: Int
                val height: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val mode = display.mode
                    width = mode.physicalWidth
                    height = mode.physicalHeight
                } else {
                    // API < 30 - Use deprecated Display API
                    val displayMetrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(displayMetrics)
                    width = displayMetrics.widthPixels
                    height = displayMetrics.heightPixels
                }

                // physicalWidth/Height follow the panel's native orientation, which is portrait on a
                // phone. Scanout geometry is landscape (gamescope wants W > H), so order it that way.
                val result = if (width >= height) "${width}x$height" else "${height}x$width"
                Timber.i("Native display size: %s (panel was %sx%s)", result, width, height)
                result
            } catch (e: Exception) {
                Timber.e(e, "Failed to get native display size")
                null
            }
        }

        @JvmStatic
        fun getDefaultScreenSize(): String {
            cachedDefaultScreenSize?.let { return it }

            return try {
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val width : Int
                    val height : Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val mode = display.mode
                        width = mode.physicalWidth
                        height = mode.physicalHeight
                    } else {
                        // API < 30 - Use deprecated Display API
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        display.getRealMetrics(displayMetrics)
                        width = displayMetrics.widthPixels
                        height = displayMetrics.heightPixels
                    }

                    // Calculate aspect ratio (always use landscape orientation for calculation)
                    val aspectRatio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()

                    // Aspect ratio thresholds:
                    // 4:3 = 1.33
                    // 16:10 = 1.6
                    // 16:9 = 1.77

                    val result = when {
                        aspectRatio < 1.5f -> Container.DEFAULT_SCREEN_SIZE_4_3  // 4:3 aspect ratio devices
                        aspectRatio < 1.7f -> Container.DEFAULT_SCREEN_SIZE_16_10  // 16:10 aspect ratio devices
                        else -> Container.DEFAULT_SCREEN_SIZE_16_9  // 16:9 and wider aspect ratio devices
                    }
                    cachedDefaultScreenSize = result
                    result
                } else {
                    val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                    cachedDefaultScreenSize = fallback
                    fallback
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get device screen size")
                val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                cachedDefaultScreenSize = fallback
                fallback
            }
        }
    }

    /**
     * Some native libraries we dlopen at runtime (libsteamclient.so via SteamBootstrap,
     * the lsfg-vk layer, etc.) depend on `libjpeg.so`, which isn't on every device's
     * dynamic linker search path. Pre-load the system copy here with RTLD_GLOBAL
     * semantics (System.load is global) so all subsequent dlopens find its symbols.
     *
     * Single place for all: runs once in Application.onCreate before any other
     * native lib is loaded by this process. Failures are non-fatal — devices that
     * don't have the file (or have it elsewhere) just fall through.
     */
    private fun preloadSystemLibraries() {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val candidates = if (is64) {
            listOf("/system/lib64/libjpeg.so", "/system/lib/libjpeg.so")
        } else {
            listOf("/system/lib/libjpeg.so", "/system/lib64/libjpeg.so")
        }
        for (path in candidates) {
            if (!File(path).exists()) continue
            try {
                System.load(path)
                Timber.i("[PluviaApp]: Preloaded $path")
                return
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: System.load($path) failed")
            }
        }
        Timber.w("[PluviaApp]: Could not preload system libjpeg.so (none of the candidate paths worked)")
    }
}
