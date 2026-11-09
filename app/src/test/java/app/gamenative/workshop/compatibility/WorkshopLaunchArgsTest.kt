package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource
import app.gamenative.workshop.WorkshopManager
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkshopLaunchArgsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun empireAtWarLoadsTheFirstEnabledItemFromItsModsFolder() {
        val override = WorkshopCompatibilityRegistry.get(GameSource.STEAM, "32470")!!
        assertEquals("corruption/Mods", override.linkModsInto)
        assertEquals("MODPATH=Mods\\1426753815", override.launchArgs!!(listOf(1426753815L, 2000000000L)))
    }

    @Test
    fun gamesWithoutAnOverrideGetNoLaunchArgs() {
        assertNull(WorkshopCompatibilityRegistry.get(GameSource.STEAM, "331470")?.launchArgs)
        assertNull(WorkshopCompatibilityRegistry.get(GameSource.STEAM, "42700"))
    }

    @Test
    fun linksPointAtTheFolderHoldingDataAndStaleLinksGo() {
        val content = tmp.newFolder("content")
        File(content, "1/Data").mkdirs() // Data at the top
        File(content, "2/BSG/Data").mkdirs() // Data one level down
        File(content, "3/Data").mkdirs()
        val mods = tmp.newFolder("game", "corruption", "Mods")
        File(mods, "MyLocalMod").mkdirs() // the player's own mod stays
        Files.createSymbolicLink(File(mods, "3").toPath(), File(content, "3").toPath()) // no longer enabled

        WorkshopManager.linkModsIntoGame(mods, content, listOf(1L, 2L), "Data")

        assertEquals(File(content, "1").absolutePath, Files.readSymbolicLink(File(mods, "1").toPath()).toString())
        assertEquals(File(content, "2/BSG").absolutePath, Files.readSymbolicLink(File(mods, "2").toPath()).toString())
        assertFalse(File(mods, "3").exists())
        assertTrue(File(mods, "MyLocalMod").isDirectory)
    }
}
