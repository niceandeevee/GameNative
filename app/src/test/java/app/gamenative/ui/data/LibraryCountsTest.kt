package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.HiddenGameFilter
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUntil
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCountsTest {

    @Test
    fun persistsPostHiddenVisibilityCounts() {
        installFakePrefManager(FakeDataStore()).use {
            // Fixture: hidden games that the default (off) setting filters out.
            val hiddenSteamAppIds = setOf(570)
            val steamAppIds = listOf(440, 570)
            val visibleSteamCount = steamAppIds.count { appId ->
                HiddenGameFilter.passesSteam(
                    appId = appId,
                    hiddenAppIds = hiddenSteamAppIds,
                    showHiddenByDefault = false,
                    hiddenCollectionSelected = false,
                )
            }

            LibraryCounts.persist(
                customGames = 2,
                steamGames = visibleSteamCount,
            )

            // Wait on actual values (not a raw write count) so PrefManager.init's async cleanup
            // writes cannot satisfy the wait early.
            awaitUntil {
                PrefManager.steamGamesCount == visibleSteamCount && PrefManager.customGamesCount == 2
            }

            assertEquals(2, PrefManager.customGamesCount)
            assertEquals(1, PrefManager.steamGamesCount)
        }
    }
}
