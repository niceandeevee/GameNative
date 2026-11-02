package app.gamenative.data

import app.gamenative.utils.Net
import app.gamenative.utils.SteamUtils
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Loads the richer, public metadata exposed by supported storefronts when a game page is opened.
 * Failures are intentionally non-fatal: the UI keeps showing metadata already synced into the library.
 */
object StoreDetailsRepository {
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_CACHE_ENTRIES = 256
    private const val TAG = "StoreDetails"

    private data class CacheEntry(
        val loadedAt: Long,
        val details: StoreGameDetails,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val httpClient: OkHttpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getDetails(
        libraryItem: LibraryItem,
        fallback: StoreGameDetails = StoreGameDetails(),
        locale: Locale = Locale.getDefault(),
    ): StoreGameDetails = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val localDetails = fallback.withoutTitleOnlyDescription(libraryItem.name)
        val localeKey = when (libraryItem.gameSource) {
            GameSource.STEAM -> SteamUtils.steamLanguageForAppLocale(locale)
            GameSource.CUSTOM_GAME -> "none"
        }
        val cacheKey = buildString {
            append(libraryItem.appId)
            append(':')
            append(localeKey)
        }
        cache[cacheKey]
            ?.takeIf { now - it.loadedAt in 0..CACHE_TTL_MS }
            ?.let {
                return@withContext mergeStoreDetails(
                    source = libraryItem.gameSource,
                    fetched = it.details,
                    local = localDetails,
                )
            }

        cache.entries.forEach { (key, entry) ->
            if (now - entry.loadedAt !in 0..CACHE_TTL_MS) cache.remove(key, entry)
        }

        val fetched = try {
            when (libraryItem.gameSource) {
                GameSource.STEAM -> fetchSteam(libraryItem.gameId, locale)
                GameSource.CUSTOM_GAME -> StoreGameDetails()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Store metadata fetch failed for ${libraryItem.appId}")
            StoreGameDetails()
        }

        if (fetched.hasContent) {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.entries.minByOrNull { it.value.loadedAt }
                    ?.let { cache.remove(it.key, it.value) }
            }
            cache[cacheKey] = CacheEntry(now, fetched)
        }
        mergeStoreDetails(
            source = libraryItem.gameSource,
            fetched = fetched,
            local = localDetails,
        )
    }

    private suspend fun fetchSteam(appId: Int, locale: Locale): StoreGameDetails = coroutineScope {
        if (appId <= 0) return@coroutineScope StoreGameDetails()
        val language = SteamUtils.steamLanguageForAppLocale(locale)
        val detailsRequest = async {
            getBody("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=$language")
        }
        val reviewsRequest = async {
            getBody(
                "https://store.steampowered.com/appreviews/$appId" +
                    "?json=1&language=all&purchase_type=all&num_per_page=0&l=$language",
            )
        }
        parseSteamStoreDetails(
            appId = appId,
            detailsJson = detailsRequest.await(),
            reviewsJson = reviewsRequest.await(),
        )
    }


    private fun getBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GameNative Android")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Store request returned ${response.code}: $url")
                    null
                } else {
                    response.body?.string()?.removePrefix("\uFEFF")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Store request failed: $url")
            null
        }
    }
}



private fun mergeStoreDetails(
    source: GameSource,
    fetched: StoreGameDetails,
    local: StoreGameDetails,
): StoreGameDetails = fetched.mergedWith(local)


private val ROMAN_NUMERALS = mapOf(
    "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7",
    "viii" to "8", "ix" to "9", "xi" to "11", "xii" to "12", "xiii" to "13",
)

/** Title key that ignores case, punctuation, edition words and roman numerals. */
private fun normalizeStoreTitle(text: String): String {
    var s = text.lowercase()
    s = s.replace(Regex("[™®©]"), "")
    s = s.replace("&", " and ")
    s = s.replace(Regex("[^a-z0-9]+"), " ")
    s = s.replace(
        Regex("\\b(goty|game of the year|complete|definitive|enhanced|deluxe|ultimate|remastered|collection|bundle)\\b"),
        "",
    )
    s = s.replace(Regex("\\bedition\\b"), "")
    s = s.split(" ").joinToString(" ") { ROMAN_NUMERALS[it] ?: it }
    return s.replace(Regex("\\s+"), " ").trim()
}

internal fun StoreGameDetails.withoutTitleOnlyDescription(title: String): StoreGameDetails {
    if (description.isBlank()) return this
    val normalizedDescription = normalizeStoreTitle(description)
    val normalizedTitle = normalizeStoreTitle(title)
    return if (normalizedDescription.isNotBlank() && normalizedDescription == normalizedTitle) {
        copy(description = "")
    } else {
        this
    }
}





internal fun parseSteamStoreDetails(
    appId: Int,
    detailsJson: String?,
    reviewsJson: String?,
): StoreGameDetails {
    val data = parseObject(detailsJson)
        ?.optJSONObject(appId.toString())
        ?.takeIf { it.optBoolean("success", false) }
        ?.optJSONObject("data")

    val description = data?.let { steamData ->
        sequenceOf(
            steamData.optString("about_the_game"),
            steamData.optString("detailed_description"),
            steamData.optString("short_description"),
        ).map(::stripStoreHtml).firstOrNull(String::isNotBlank).orEmpty()
    }.orEmpty()

    val genres = data?.optJSONArray("genres").objects()
        .map { it.optString("description") }
    val categories = data?.optJSONArray("categories").objects()
        .map { it.optString("description") }

    val reviewSummary = parseObject(reviewsJson)?.optJSONObject("query_summary")
    val reviewCount = reviewSummary?.optInt("total_reviews", 0)?.takeIf { it > 0 }
    val positiveCount = reviewSummary?.optInt("total_positive", 0) ?: 0
    val reviewPercentage = reviewCount?.let { (positiveCount * 100.0 / it).roundToInt().coerceIn(0, 100) }

    return StoreGameDetails(
        description = description,
        reviewPercentage = reviewPercentage,
        reviewCount = reviewCount,
        reviewSummary = reviewSummary?.optString("review_score_desc")?.takeIf(String::isNotBlank),
        tags = (genres + categories).cleanStoreList(16),
    )
}



private fun parseObject(value: String?): JSONObject? = value
    ?.takeIf(String::isNotBlank)
    ?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.labels(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is String -> add(value)
                is JSONObject -> sequenceOf(
                    value.optString("name"),
                    value.optString("title"),
                    value.optString("label"),
                ).firstOrNull(String::isNotBlank)?.let(::add)
            }
        }
    }
}

private fun String.normalizeProtocolUrl(): String? {
    val value = trim()
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("https://") || value.startsWith("http://") -> value
        else -> null
    }
}

private fun List<String>.cleanStoreList(limit: Int): List<String> = asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(Locale.ROOT) }
    .take(limit)
    .toList()

internal fun stripStoreHtml(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(?:p|div|li|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()
}
