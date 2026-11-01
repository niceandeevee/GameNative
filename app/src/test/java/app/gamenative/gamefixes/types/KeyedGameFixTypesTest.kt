package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedGameFixTypesTest {
    @Test
    fun keyedRegistryFix_exposesSourceAndId_forRegistryLookup() {
        val fix = STEAM_Fix_22330

        assertTrue(fix is KeyedRegistryKeyFix)
        assertEquals(GameSource.STEAM, fix.gameSource)
        assertEquals("22330", fix.gameId)
    }

    @Test
    fun keyedVulkanExtensionBlacklistFix_exposesSourceAndId_forRegistryLookup() {
        val fix = STEAM_Fix_658920

        assertTrue(fix is KeyedVulkanExtensionBlacklistFix)
        assertEquals(GameSource.STEAM, fix.gameSource)
        assertEquals("658920", fix.gameId)
    }
}
