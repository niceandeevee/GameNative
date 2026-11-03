package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GameFixesRegistryTest {
    private lateinit var context: Context
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        mockkObject(CustomGameScanner)
    }

    @After
    fun tearDown() {
        GameFixesRegistry.setFixesProviderForTests(null)
        unmockkAll()
    }

    @Test
    fun applyFor_appliesWowForeverDxvkFix_whenCustomGameExeMatches() {
        val installDir = createTempDirectory(prefix = "registry-wow-forever").toFile()
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "_retail_\\WowB-ARM64.exe"
        every { container.drives } returns "D:/sdcard/Download/A:${installDir.absolutePath}"
        every { container.containerVariant } returns Container.BIONIC
        every { container.dxWrapper } returns "dxvk"
        every { container.dxWrapperConfig } returns "version=2.4.1,framerate=0,maxDeviceMemory=0"
        every { container.dxWrapperConfig = any() } just runs
        every { container.saveData() } just runs

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        verify(exactly = 1) {
            container.dxWrapperConfig = "version=$WOW_FOREVER_DXVK_VERSION,framerate=0,maxDeviceMemory=0"
        }
        verify(exactly = 1) { container.saveData() }
    }

    @Test
    fun applyFor_doesNothing_whenCustomGameExeIsNotWowForever() {
        val installDir = createTempDirectory(prefix = "registry-custom-other").toFile()
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "Game.exe"

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        verify(exactly = 0) { container.dxWrapperConfig = any() }
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun clearAppliedMarks_removesOnlyFixMarks() {
        val extras = org.json.JSONObject()
            .put("steamClientRequiredFixApplied", "1")
            .put("dxWrapperDefaultFixApplied", "dxvk;maxDeviceMemory=1024")
            .put("audioDriverDefaultFixApplied", "alsa")
            .put("profileId", "7")
        every { container.extraData } returns extras

        GameFixesRegistry.clearAppliedMarks(container)

        assertEquals(listOf("profileId"), extras.keys().asSequence().toList())
    }

    @Test
    fun applyFor_clearsMarksBeforeApplying_whenWineVersionChanged() {
        val installDir = createTempDirectory(prefix = "registry-wine-change").toFile()
        val extras = org.json.JSONObject().put("audioDriverDefaultFixApplied", "alsa")
        var marksSeenByFix: List<String>? = null
        GameFixesRegistry.setFixesProviderForTests {
            mapOf(
                (app.gamenative.data.GameSource.CUSTOM_GAME to "game.exe") to object : GameFix {
                    override fun apply(
                        context: Context,
                        gameId: String,
                        installPath: String,
                        installPathWindows: String,
                        container: Container,
                    ): Boolean {
                        marksSeenByFix = extras.keys().asSequence().toList()
                        return true
                    }
                },
            )
        }
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "Game.exe"
        every { container.drives } returns "A:${installDir.absolutePath}"
        every { container.extraData } returns extras
        every { container.wineVersion } returns "proton-11.0-2-arm64ec-9"
        every { container.getExtra("gameFixesWineVersion") } returns "proton-9.0-x86_64"

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        assertEquals(emptyList<String>(), marksSeenByFix)
        verify { container.putExtra("gameFixesWineVersion", "proton-11.0-2-arm64ec-9") }
    }

    @Test
    fun applyFor_keepsMarks_whenWineVersionUnchanged() {
        val installDir = createTempDirectory(prefix = "registry-wine-same").toFile()
        val extras = org.json.JSONObject().put("audioDriverDefaultFixApplied", "alsa")
        GameFixesRegistry.setFixesProviderForTests {
            mapOf(
                (app.gamenative.data.GameSource.CUSTOM_GAME to "game.exe") to object : GameFix {
                    override fun apply(
                        context: Context,
                        gameId: String,
                        installPath: String,
                        installPathWindows: String,
                        container: Container,
                    ): Boolean = true
                },
            )
        }
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_123") } returns installDir.absolutePath
        every { CustomGameScanner.getLaunchExecutable(container) } returns "Game.exe"
        every { container.drives } returns "A:${installDir.absolutePath}"
        every { container.extraData } returns extras
        every { container.wineVersion } returns "proton-11.0-2-arm64ec-9"
        every { container.getExtra("gameFixesWineVersion") } returns "proton-11.0-2-arm64ec-9"

        GameFixesRegistry.applyFor(context, "CUSTOM_GAME_123", container)

        assertEquals(listOf("audioDriverDefaultFixApplied"), extras.keys().asSequence().toList())
    }
}
