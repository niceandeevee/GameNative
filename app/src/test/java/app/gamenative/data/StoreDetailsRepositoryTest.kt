package app.gamenative.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDetailsRepositoryTest {

    @Test
    fun `steam parser returns description reviews and tags`() {
        val details =
            """
            {
              "123": {
                "success": true,
                "data": {
                  "about_the_game": "<p>Fight &amp; win.</p><p>Bring a friend.</p>",
                  "genres": [{"description": "Action"}],
                  "categories": [{"description": "Co-op"}]
                }
              }
            }
            """.trimIndent()
        val reviews =
            """
            {
              "query_summary": {
                "total_positive": 84,
                "total_reviews": 100,
                "review_score_desc": "Very Positive"
              }
            }
            """.trimIndent()

        val result = parseSteamStoreDetails(123, details, reviews)

        assertEquals("Fight & win.\nBring a friend.", result.description)
        assertEquals(84, result.reviewPercentage)
        assertEquals(100, result.reviewCount)
        assertEquals("Very Positive", result.reviewSummary)
        assertEquals(listOf("Action", "Co-op"), result.tags)
    }

    @Test
    fun `steam parser skips empty markup when choosing a description`() {
        val details =
            """
            {
              "123": {
                "success": true,
                "data": {
                  "about_the_game": "<br><p> </p>",
                  "detailed_description": "<p>The actual description.</p>"
                }
              }
            }
            """.trimIndent()

        val result = parseSteamStoreDetails(123, details, null)

        assertEquals("The actual description.", result.description)
    }

    @Test
    fun `remote details retain missing local fallback fields`() {
        val remote = StoreGameDetails(
            description = "Fresh description",
            tags = listOf("Action"),
        )
        val local = StoreGameDetails(
            description = "Old description",
            reviewPercentage = 91,
            tags = listOf("action", "Co-op"),
        )

        val result = remote.mergedWith(local)

        assertEquals("Fresh description", result.description)
        assertEquals(91, result.reviewPercentage)
        assertEquals(listOf("Action", "Co-op"), result.tags)
    }

    @Test
    fun `title-only catalog description is discarded`() {
        val details = StoreGameDetails(description = "The Example: Deluxe Edition")

        val result = details.withoutTitleOnlyDescription("The Example")

        assertEquals("", result.description)
    }

}
