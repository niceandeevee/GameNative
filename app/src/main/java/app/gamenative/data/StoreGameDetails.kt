package app.gamenative.data

import java.util.Locale

/** Storefront metadata shown on an owned game's library detail page. */
data class StoreGameDetails(
    val description: String = "",
    val reviewPercentage: Int? = null,
    val reviewCount: Int? = null,
    val reviewSummary: String? = null,
    val tags: List<String> = emptyList(),
) {
    val hasOverview: Boolean
        get() = description.isNotBlank() || reviewPercentage != null || tags.isNotEmpty()

    val hasContent: Boolean
        get() = hasOverview

    /** Prefer newly fetched storefront values while retaining useful local catalog fallbacks. */
    fun mergedWith(fallback: StoreGameDetails): StoreGameDetails = StoreGameDetails(
        description = description.ifBlank { fallback.description },
        reviewPercentage = reviewPercentage ?: fallback.reviewPercentage,
        reviewCount = reviewCount ?: fallback.reviewCount,
        reviewSummary = reviewSummary?.takeIf { it.isNotBlank() }
            ?: fallback.reviewSummary?.takeIf { it.isNotBlank() },
        tags = (tags + fallback.tags).cleanList(MAX_TAGS),
    )

    private fun List<String>.cleanList(limit: Int): List<String> = asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(limit)
        .toList()

    private companion object {
        const val MAX_TAGS = 16
    }
}
