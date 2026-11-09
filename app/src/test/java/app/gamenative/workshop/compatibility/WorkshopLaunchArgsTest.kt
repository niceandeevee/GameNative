package app.gamenative.workshop.compatibility

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopLaunchArgsTest {
    @Test
    fun empireAtWarLoadsTheFirstEnabledItemWithModpath() {
        val build = WorkshopCompatibilityRegistry.get(GameSource.STEAM, "32470")?.launchArgs
        assertEquals(
            "MODPATH=..\\..\\..\\workshop\\content\\32470\\1426753815",
            build!!(listOf(1426753815L, 2000000000L)),
        )
    }

    @Test
    fun gamesWithoutAnOverrideGetNoLaunchArgs() {
        assertNull(WorkshopCompatibilityRegistry.get(GameSource.STEAM, "331470")?.launchArgs)
        assertNull(WorkshopCompatibilityRegistry.get(GameSource.STEAM, "42700"))
    }
}
