package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import timber.log.Timber

object ManifestRepository {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/utkarshdalal/GameNative/refs/heads/master/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    suspend fun loadManifest(context: Context): ManifestData {
        if (BuildConfig.DEBUG) {
            readLocalManifest(context)?.let {
                Timber.i("ManifestRepository: using local debug manifest")
                return it
            }
        }

        val cachedJson = PrefManager.componentManifestJson
        val cachedManifest = parseManifest(cachedJson) ?: ManifestData.empty()
        val lastFetchedAt = PrefManager.componentManifestFetchedAt
        val isStale = System.currentTimeMillis() - lastFetchedAt >= ONE_DAY_MS

        if (cachedJson.isNotEmpty() && !isStale) {
            return cachedManifest
        }

        // A usable cache is served immediately and refreshed in the background, so a game launch
        // never waits on GitHub; only a first run with no cache blocks on the fetch.
        if (parseManifest(cachedJson) != null) {
            refreshScope.launch { refresh() }
            return cachedManifest
        }

        return refresh() ?: cachedManifest
    }

    private suspend fun refresh(): ManifestData? = refreshLock.withLock {
        if (PrefManager.componentManifestJson.isNotEmpty() &&
            System.currentTimeMillis() - PrefManager.componentManifestFetchedAt < ONE_DAY_MS
        ) {
            return@withLock parseManifest(PrefManager.componentManifestJson)
        }
        val fetched = fetchManifestJson() ?: return@withLock null
        val parsed = parseManifest(fetched) ?: return@withLock null
        PrefManager.componentManifestJson = fetched
        PrefManager.componentManifestFetchedAt = System.currentTimeMillis()
        parsed
    }

    private suspend fun fetchManifestJson(): String? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(MANIFEST_URL).build()
        Net.http.newCall(request).execute().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    } catch (e: Exception) {
        Timber.e(e, "ManifestRepository: fetch failed")
        null
    }
}

    private fun readLocalManifest(context: Context): ManifestData? = try {
        context.assets.open("manifest.json").bufferedReader().use { parseManifest(it.readText()) }
    } catch (e: Exception) {
        null
    }

    fun parseManifest(jsonString: String?): ManifestData? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ManifestData>(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: parse failed")
            null
        }
    }
}
