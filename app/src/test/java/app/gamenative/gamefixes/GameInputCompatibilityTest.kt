package app.gamenative.gamefixes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputCompatibilityTest {
    @Test
    fun `Crushed in Time enables mouse drag compatibility`() {
        assertTrue(GameInputCompatibility.needsMouseDragCompatibility("STEAM_3858650"))
    }

    @Test
    fun `Black Ops uses the regular X pointer path`() {
        assertFalse(GameInputCompatibility.needsWin32RelativeMouse("STEAM_42700"))
        assertFalse(GameInputCompatibility.needsWin32RelativeMouse("STEAM_42710"))
        assertFalse(GameInputCompatibility.needsMouseDragCompatibility("STEAM_42700"))
    }

    @Test
    fun `mouse drag compatibility remains disabled for other games`() {
        assertFalse(GameInputCompatibility.needsMouseDragCompatibility("STEAM_3858651"))
    }
}
