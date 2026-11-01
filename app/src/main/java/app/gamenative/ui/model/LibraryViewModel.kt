package app.gamenative.ui.model

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.FavoritesManager
import app.gamenative.data.FavoritesUtils
import app.gamenative.data.GameSource
import app.gamenative.data.HiddenGameFilter
import app.gamenative.data.LibraryItem
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.events.AndroidEvent
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.steam.SteamCollectionFilter
import app.gamenative.steam.curated.CuratedListDescriptor
import app.gamenative.steam.curated.CuratedListRepository
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.LibraryCounts
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameImporter
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.unaccent
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong


@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val steamAppDao: SteamAppDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState(isLoading = true))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        onFilterApps(paginationCurrentPage)
    }

    private val onAllowedAppsLoaded: (AndroidEvent.AllowedAppsLoaded) -> Unit = {
        onFilterApps(paginationCurrentPage)
    }

    private val onPreferredCopyChanged: (AndroidEvent.PreferredCopyChanged) -> Unit = { event ->
        viewModelScope.launch(Dispatchers.IO) {
            val updated = steamAppDao.findApp(event.appId) ?: return@launch
            if (appList.any { it.id == updated.id }) {
                appList = appList.map { if (it.id == updated.id) updated else it }
                onFilterApps(paginationCurrentPage)
            }
        }
    }

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        // Increment refresh counter and refresh the library list to pick up newly fetched images
        _state.update { it.copy(imageRefreshCounter = it.imageRefreshCounter + 1) }
        onFilterApps(paginationCurrentPage)
    }

    private val onHiddenGamesSettingChanged: (AndroidEvent.HiddenGamesSettingChanged) -> Unit = { event ->
        // Use the value from the event rather than re-reading PrefManager: its DataStore write is
        // asynchronous, so reading it here can race and re-filter with the old value.
        showHiddenGamesByDefault = event.showHiddenGamesByDefault
        onFilterApps(paginationCurrentPage)
    }

    private val onLibraryTabsChanged: (AndroidEvent.LibraryTabsChanged) -> Unit = { event ->
        updateVisibleLibraryTabs(event.visibleTabs)
    }

    // How many items loaded on one page of results
    @Volatile private var paginationCurrentPage: Int = 0
    @Volatile private var lastPageInCurrentFilter: Int = 0

    // App ids across every source the Favorites tab shows, cached from the last filter pass so a
    // favorite toggle can update the badge count without rebuilding the whole library list when
    // the user isn't on the Favorites tab.
    @Volatile private var favoriteEligibleAppIds: Set<String> = emptySet()

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()
    private var playHistoryByAppId: Map<String, Long> = emptyMap()

    @Volatile private var steamCollections: List<SteamCollection>? = null

    // Mirrors PrefManager.showHiddenGamesByDefault without the async DataStore write race.
    @Volatile private var showHiddenGamesByDefault: Boolean = PrefManager.showHiddenGamesByDefault

    @Volatile private var curatedLists: List<SteamCollection>? = null
    private val curatedSelectionLock = Any()

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce
    private var filterJob: Job? = null
    private val filterGeneration = AtomicLong(0L)

    // Cache GPU name to avoid repeated calls
    init {
        // Keep the Favorites tab and its badge in sync as the user stars or unstars games. When the
        // user is actually viewing the Favorites tab we rebuild the list so its contents change;
        // otherwise only the badge count can change, so we update that cheaply instead of running a
        // full (and visibly loading) re-filter of the entire library.
        viewModelScope.launch(Dispatchers.IO) {
            FavoritesManager.favorites
                .drop(1)
                .collectLatest { favorites ->
                    if (_state.value.currentTab == LibraryTab.FAVORITES) {
                        onFilterApps(paginationCurrentPage).join()
                    } else {
                        val count = FavoritesUtils.countPresent(favorites, favoriteEligibleAppIds)
                        _state.update { it.copy(favoritesCount = count) }
                    }
                }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch(Dispatchers.IO) {
            // Re-create the underlying DAO Flow whenever the EXPIRED filter is toggled,
            // so apps with Expired or missing licenses are surfaced/hidden accordingly.
            _state
                .map { it.appInfoSortType.contains(AppFilter.EXPIRED) }
                .distinctUntilChanged()
                .flatMapLatest { includeExpired ->
                    steamAppDao.getAllOwnedApps(includeExpired = includeExpired)
                }
                .collect { apps ->
                    Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                    // Check if the list has actually changed before triggering a re-filter
                    if (appList != apps) {
                        appList = apps
                        onFilterApps(paginationCurrentPage)
                    }
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            libraryPlayHistoryDao.getAll().collect { entries ->
                val playHistory = entries.associate { it.appId to it.lastPlayed }
                if (playHistoryByAppId != playHistory) {
                    playHistoryByAppId = playHistory
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Load any cached collections immediately, then observe live updates.
        SteamCollectionRepository.loadFromCache()
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.collections.collect { collections ->
                steamCollections = collections
                // Reconcile the persisted selection against freshly-loaded collections.
                val current = _state.value.selectedSteamCollectionIds
                val recon = SteamCollectionFilter.reconcile(current, collections)
                if (recon.removedAny) {
                    PrefManager.librarySteamCollections = recon.cleaned
                }
                _state.update {
                    it.copy(
                        steamCollections = collections,
                        selectedSteamCollectionIds = recon.cleaned,
                    )
                }
                if (recon.removedAny) {
                    SnackbarManager.show(context.getString(R.string.steam_collections_removed))
                }
                onFilterApps(paginationCurrentPage)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.skippedDynamic.collect { skipped ->
                _state.update { it.copy(skippedDynamicCollections = skipped) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            CuratedListRepository.curatedLists.filterNotNull().collect { raw ->
                val resolved = buildCuratedCollections(raw)
                curatedLists = resolved
                synchronized(curatedSelectionLock) {
                    var cleanedSelection: Set<String>? = null
                    _state.update { currentState ->
                        val recon = SteamCollectionFilter.reconcile(
                            currentState.selectedCuratedListIds,
                            resolved,
                        )
                        cleanedSelection = if (recon.removedAny) recon.cleaned else null
                        currentState.copy(
                            curatedLists = resolved,
                            selectedCuratedListIds = recon.cleaned,
                        )
                    }
                    cleanedSelection?.let { PrefManager.libraryCuratedLists = it }
                }
                onFilterApps(paginationCurrentPage)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            CuratedListRepository.loadFromCache()
            CuratedListRepository.refreshFourThreeIfNeeded()
        }

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.PreferredCopyChanged, Unit>(onPreferredCopyChanged)
        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.on<AndroidEvent.HiddenGamesSettingChanged, Unit>(onHiddenGamesSettingChanged)
        PluviaApp.events.on<AndroidEvent.LibraryTabsChanged, Unit>(onLibraryTabsChanged)
        PluviaApp.events.on<AndroidEvent.AllowedAppsLoaded, Unit>(onAllowedAppsLoaded)

    }

    private fun buildCuratedCollections(raw: Map<String, Set<Int>>): List<SteamCollection> {
        return CuratedListDescriptor.entries.mapNotNull { descriptor ->
            val appIds = raw[descriptor.id] ?: return@mapNotNull null
            SteamCollection(
                id = descriptor.id,
                name = context.getString(descriptor.nameRes),
                appIds = appIds,
            )
        }
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.PreferredCopyChanged, Unit>(onPreferredCopyChanged)
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.off<AndroidEvent.HiddenGamesSettingChanged, Unit>(onHiddenGamesSettingChanged)
        PluviaApp.events.off<AndroidEvent.LibraryTabsChanged, Unit>(onLibraryTabsChanged)
        PluviaApp.events.off<AndroidEvent.AllowedAppsLoaded, Unit>(onAllowedAppsLoaded)
        super.onCleared()
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSourceToggle(source: GameSource) {
        val current = _state.value
        when (source) {
            GameSource.STEAM -> {
                val newValue = !current.showSteamInLibrary
                PrefManager.showSteamInLibrary = newValue
                _state.update { it.copy(showSteamInLibrary = newValue) }
            }

            GameSource.CUSTOM_GAME -> {
                val newValue = !current.showCustomGamesInLibrary
                PrefManager.showCustomGamesInLibrary = newValue
                _state.update { it.copy(showCustomGamesInLibrary = newValue) }
            }
        }
        onFilterApps(paginationCurrentPage)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        PrefManager.librarySortOption = sortOption
        _state.update { it.copy(currentSortOption = sortOption) }
        onFilterApps()
    }

    fun onOptionsPanelToggle(isOpen: Boolean) {
        _state.update { it.copy(isOptionsPanelOpen = isOpen) }
    }

    fun onTabChanged(tab: LibraryTab) {
        if (tab !in _state.value.visibleLibraryTabs) return
        _state.update { it.copy(currentTab = tab) }
        onFilterApps(0) // Reset to first page and refresh
    }

    fun onNextTab() {
        _state.update { currentState ->
            val nextTab = currentState.currentTab.next(currentState.visibleLibraryTabs)
            Timber.tag("LibraryViewModel").d("Tab next via bumper: ${currentState.currentTab} -> $nextTab")
            currentState.copy(currentTab = nextTab)
        }
        onFilterApps(0)
    }

    fun onPreviousTab() {
        _state.update { currentState ->
            val previousTab = currentState.currentTab.previous(currentState.visibleLibraryTabs)
            Timber.tag("LibraryViewModel").d("Tab previous via bumper: ${currentState.currentTab} -> $previousTab")
            currentState.copy(currentTab = previousTab)
        }
        onFilterApps(0)
    }

    private fun updateVisibleLibraryTabs(visibleTabs: List<LibraryTab>) {
        var tabChanged = false
        _state.update { currentState ->
            val currentTab = if (currentState.currentTab in visibleTabs) {
                currentState.currentTab
            } else {
                tabChanged = true
                LibraryTab.ALL
            }
            currentState.copy(
                currentTab = currentTab,
                visibleLibraryTabs = visibleTabs,
            )
        }
        if (tabChanged) onFilterApps(0)
    }

    fun onSearchQuery(value: String) {
        // Update UI immediately for responsive typing
        _state.update { it.copy(searchQuery = value) }

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps()
        }
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps()
    }

    fun onSteamCollectionToggle(id: String) {
        _state.update { currentState ->
            val updated = currentState.selectedSteamCollectionIds.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            PrefManager.librarySteamCollections = updated
            currentState.copy(selectedSteamCollectionIds = updated)
        }
        onFilterApps()
    }

    fun onClearSteamCollections() {
        _state.update { currentState ->
            PrefManager.librarySteamCollections = emptySet()
            currentState.copy(selectedSteamCollectionIds = emptySet())
        }
        onFilterApps()
    }

    fun onCuratedListToggle(id: String) {
        synchronized(curatedSelectionLock) {
            var updatedSelection: Set<String> = emptySet()
            _state.update { currentState ->
                val updated = currentState.selectedCuratedListIds.toMutableSet()
                if (!updated.add(id)) updated.remove(id)
                updatedSelection = updated
                currentState.copy(selectedCuratedListIds = updated)
            }
            PrefManager.libraryCuratedLists = updatedSelection
        }
        onFilterApps()
    }

    fun onClearCuratedLists() {
        synchronized(curatedSelectionLock) {
            PrefManager.libraryCuratedLists = emptySet()
            _state.update { currentState ->
                currentState.copy(selectedCuratedListIds = emptySet())
            }
        }
        onFilterApps()
    }

    fun onPageChange(pageIncrement: Int) {
        // Amount to change by
        var toPage = max(0, paginationCurrentPage + pageIncrement)
        toPage = min(toPage, lastPageInCurrentFilter)
        onFilterApps(toPage)
    }

    fun onRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }

            try {
                val newApps = SteamService.refreshOwnedGamesFromServer()
                if (newApps > 0) {
                    Timber.tag("LibraryViewModel").i("Queued $newApps newly owned games for PICS sync")
                } else {
                    Timber.tag("LibraryViewModel").d("No newly owned games discovered during refresh")
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Failed to refresh owned games from server")
            } finally {
                onFilterApps(0).join()
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    data class CustomGameImportState(
        val isImporting: Boolean = false,
        val progress: CustomGameImporter.Progress? = null,
    )

    private val _importState = MutableStateFlow(CustomGameImportState())
    val importState: StateFlow<CustomGameImportState> = _importState.asStateFlow()

    // Runs in viewModelScope so the copy survives configuration changes; a scope tied to the
    // composition would abort a "remove original" import partway through the move
    fun importCustomGame(uri: Uri, removeOriginal: Boolean) {
        if (_importState.value.isImporting) return
        _importState.value = CustomGameImportState(isImporting = true)
        viewModelScope.launch(Dispatchers.IO) {
            var lastShown = 0L
            val result = CustomGameImporter.importFromTreeUri(context, uri, removeOriginal) { progress ->
                if (progress.copiedBytes - lastShown > 8_000_000L) {
                    lastShown = progress.copiedBytes
                    _importState.value = CustomGameImportState(isImporting = true, progress = progress)
                }
            }
            _importState.value = CustomGameImportState()
            result.onSuccess { path ->
                addCustomGameFolder(path)
                SnackbarManager.show(context.getString(R.string.custom_game_import_success))
            }.onFailure {
                SnackbarManager.show(context.getString(R.string.custom_game_import_failed))
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            CustomGameScanner.invalidateCache()
            onFilterApps(paginationCurrentPage)
        }
    }

    private fun onFilterApps(paginationPage: Int = 0): Job {
        val generation = filterGeneration.incrementAndGet()
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        filterJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            if (generation != filterGeneration.get()) return@launch
            _state.update { it.copy(isLoading = true) }

            val currentState = _state.value
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            // Fetch download directory apps once on IO thread and cache as a HashSet for O(1) lookups
            val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps() + SteamService.getImportedAppDirs()
            val downloadDirectorySet = downloadDirectoryApps.toHashSet()

            val allowedApps = SteamService.parentalControlState.resolveAllowedAppIds { PrefManager.parentalAllowedAppIds }

            val steamOwnerTypeFiltered: List<SteamApp> = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        // Handle the case where userSteamId might be null
                        SteamService.userSteamId?.let { steamId ->
                            listOf(steamId.accountID.toInt())
                        } ?: emptyList()
                    }.let { owners ->
                        if (owners.isEmpty()) {
                            true // no owner info ⇒ don't filter the item out
                        } else {
                            owners.any { item.ownerAccountId.contains(it) }
                        }
                    }
                }
                .filter { item ->
                    allowedApps?.contains(item.id) ?: true
                }
                .filter { item ->
                    val inTypeBucket = !item.isVrOnly && currentFilter.any { item.type == it }
                    val inVrBucket = item.isVrGame && currentState.appInfoSortType.contains(AppFilter.VR)
                    inTypeBucket || inVrBucket
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(PrefManager.steamUserAccountId) || PrefManager.steamUserAccountId == 0
                    }
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(item.name, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { item ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        downloadDirectorySet.contains(SteamService.getAppDirName(item))
                    } else {
                        true
                    }
                }
                .toList()

            // Apply the Steam collection filter — union/OR, fail-open (see SteamCollectionFilter).
            // Curated-list selections use the same rules, then passesAll intersects the sections.
            // Resolve the allowed app-id set once for the whole pass instead of per app.
            val allowedSteamAppIds = SteamCollectionFilter.allowedAppIds(
                selectedIds = currentState.selectedSteamCollectionIds,
                collections = steamCollections,
            )
            // Default hidden filtering: hidden Steam games stay out unless the setting is on or the
            // Hidden collection is explicitly selected. Unloaded collections fail open (empty set).
            val hiddenSteamAppIds = steamCollections
                ?.firstOrNull { it.id == SteamCollection.ID_HIDDEN }
                ?.appIds
                ?: emptySet()
            val hiddenCollectionSelected = currentState.selectedSteamCollectionIds.contains(SteamCollection.ID_HIDDEN)

            // Per-collection counts: the Hidden collection keeps its full pre-hidden count (so it
            // stays discoverable), while every other collection counts only visible games so its
            // badge matches what is rendered when selected.
            val preHiddenAppIds = steamOwnerTypeFiltered.map { it.id }
            val visibleAppIds = preHiddenAppIds.filter { appId ->
                HiddenGameFilter.passesSteam(
                    appId = appId,
                    hiddenAppIds = hiddenSteamAppIds,
                    showHiddenByDefault = showHiddenGamesByDefault,
                    hiddenCollectionSelected = hiddenCollectionSelected,
                )
            }
            val steamCollectionCounts: Map<String, Int> = SteamCollectionFilter.visibleCollectionCounts(
                collections = steamCollections,
                visibleAppIds = visibleAppIds,
                preHiddenAppIds = preHiddenAppIds,
            )

            // Curated-list counts use the same visible set as the rendered library. Unlike the
            // built-in Hidden collection, curated lists do not need a full pre-hidden count.
            val curatedListCounts: Map<String, Int> = curatedLists?.associate { collection ->
                collection.id to visibleAppIds.count { it in collection.appIds }
            } ?: emptyMap()

            val allowedCuratedAppIds = SteamCollectionFilter.allowedAppIds(
                selectedIds = currentState.selectedCuratedListIds,
                collections = curatedLists,
            )
            val steamFilteredBeforeCompatibility: List<SteamApp> = steamOwnerTypeFiltered
                .filter { app ->
                    SteamCollectionFilter.passesAll(app.id, allowedSteamAppIds, allowedCuratedAppIds)
                }
                .filter { item ->
                    HiddenGameFilter.passesSteam(
                        appId = item.id,
                        hiddenAppIds = hiddenSteamAppIds,
                        showHiddenByDefault = showHiddenGamesByDefault,
                        hiddenCollectionSelected = hiddenCollectionSelected,
                    )
                }

            // Filter Steam apps first (no pagination yet)
            // Note: Don't sort individual lists - we'll sort the combined list for consistent ordering
            val filteredSteamApps: List<SteamApp> = steamFilteredBeforeCompatibility
                .asSequence()
                .sortedWith(
                    compareByDescending<SteamApp> {
                        downloadDirectorySet.contains(SteamService.getAppDirName(it))
                    }.thenBy { it.name.lowercase() },
                )
                .toList()

            // Map Steam apps to UI items
            data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean, val lastPlayed: Long = 0L)

            fun lastPlayedFor(appId: String): Long = playHistoryByAppId[appId] ?: 0L

            val licensedDepotMap = SteamService.buildLicensedDepotMap(filteredSteamApps)

            // Added this to avoid duplicate from custom imported steam game
            val steamEntriesAppIds = mutableSetOf<String>()

            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(item))
                val installedBranch = if (isInstalled) {
                    SteamService.getInstalledApp(item.id)?.branch ?: "public"
                } else {
                    "public"
                }
                // base-game size: ownedDlc=emptyMap excludes DLC depots
                val licensedDepots = licensedDepotMap[item.id]
                val resolved = SteamService.resolveDownloadableDepots(item.depots, "", emptyMap(), licensedDepots)
                val totalSizeBytes = resolved.values.sumOf { depot ->
                    depot.manifests[installedBranch]?.size ?: depot.manifests.values.firstOrNull()?.size ?: 0L
                }

                // Move appId here
                val appId = "${GameSource.STEAM.name}_${item.id}"
                steamEntriesAppIds.add(appId)

                LibraryEntry(
                    item = LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = appId,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        sizeBytes = totalSizeBytes,
                    ),
                    isInstalled = isInstalled,
                    lastPlayed = lastPlayedFor(appId),
                )
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                CustomGameScanner.scanAsLibraryItems(
                    query = currentState.searchQuery,
                )
            } else {
                emptyList()
            }
            val customEntries = customGameItems
                .filter { !steamEntriesAppIds.contains(it.appId) } // Filter out imported steam appId
                .map { LibraryEntry(it, true, lastPlayed = lastPlayedFor(it.appId)) }

            // Save game counts for skeleton loaders (only when not searching, to get accurate counts)
            // This needs to happen before filtering by source, so we save the total counts
            if (currentState.searchQuery.isEmpty()) {
                // The lists passed here are already post-hidden, so persisted counts never include
                // games hidden by default.
                LibraryCounts.persist(
                    customGames = customGameItems.size,
                    steamGames = steamFilteredBeforeCompatibility.size,
                )
                Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${steamFilteredBeforeCompatibility.size}")
            }

            // Compute effective source filters based on current tab
            // ALL tab uses user preferences, other tabs override with their presets
            // Use captured currentState (not _state.value) to avoid TOCTOU race
            val currentTab = currentState.currentTab
            val includeSteam = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showSteamInLibrary
            } else {
                currentTab.showSteam
            }
            val includeOpen = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showCustomGamesInLibrary
            } else {
                currentTab.showCustom
            }

            // Combine both lists and apply sort option
            val sortComparator: Comparator<LibraryEntry> = when (currentState.currentSortOption) {
                SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.item.name.lowercase() }

                SortOption.NAME_ASC -> compareBy { it.item.name.lowercase() }

                SortOption.NAME_DESC -> compareByDescending { it.item.name.lowercase() }

                SortOption.RECENTLY_PLAYED -> LibrarySortUtils.recentlyPlayedComparator(
                    name = { it.item.name },
                    isInstalled = { it.isInstalled },
                    lastPlayed = { it.lastPlayed },
                )

                SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

            }

            // A Steam collection can only contain Steam apps, so when one is selected the non-Steam
            // sources can't match it — keep them out of the combined list (and their tab counts).
            // Curated lists have the same source restriction.
            val steamListFilterSelected = allowedSteamAppIds != null || allowedCuratedAppIds != null

            val favoriteIds = FavoritesManager.favorites.value

            val combined = buildList {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen && !steamListFilterSelected) addAll(customEntries)
            }.let { entries ->
                if (currentTab == app.gamenative.ui.enums.LibraryTab.FAVORITES) {
                    FavoritesUtils.filter(entries, favoriteIds) { it.item.appId }
                } else {
                    entries
                }
            }.sortedWith(sortComparator).mapIndexed { idx, entry ->
                entry.item.copy(index = idx, isInstalled = entry.isInstalled)
            }

            // A newer refresh may have taken a snapshot while this pass was doing the expensive
            // filtering. Never let this pass publish its obsolete list or pagination metadata.
            if (generation != filterGeneration.get()) return@launch

            // Total count for the current filter
            val totalFound = combined.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            lastPageInCurrentFilter = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            // Clamp the requested page to the valid range. Removing favorites (or any other filter
            // change) can shrink the list so the previously shown page no longer exists; without
            // this the pager could report a current page past the last one.
            val clampedPage = paginationPage.coerceIn(0, lastPageInCurrentFilter)
            // Update internal pagination state
            paginationCurrentPage = clampedPage
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((clampedPage + 1) * pageSize, totalFound)
            val pagedList = combined.take(endIndex)

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): $totalFound")

            if (isFirstLoad) {
                isFirstLoad = false
            }

            if (generation != filterGeneration.get()) return@launch

            // App ids across every source the Favorites tab shows. Cache it so a later favorite
            // toggle can recount the badge cheaply, and use it here so the badge matches the tab
            // contents even when a source is hidden from the library through user preferences.
            val favoriteEligible = buildList {
                addAll(steamEntries)
                addAll(customEntries)
            }.mapTo(mutableSetOf()) { it.item.appId }
            if (generation != filterGeneration.get()) return@launch
            favoriteEligibleAppIds = favoriteEligible

            _state.update {
                it.copy(
                    appInfoList = pagedList,
                    currentPaginationPage = clampedPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                    isLoading = false, // Loading complete
                    // Per-source counts for tab badges
                    // Use user prefs + auth state only (not current tab) so badges stay stable across tab switches
                    allCount = (if (currentState.showSteamInLibrary) steamEntries.size else 0) +
                        (if (currentState.showCustomGamesInLibrary) customEntries.size else 0),
                    steamCount = if (currentState.showSteamInLibrary) steamEntries.size else 0,
                    localCount = if (currentState.showCustomGamesInLibrary) customEntries.size else 0,
                    steamCollectionCounts = steamCollectionCounts,
                    curatedListCounts = curatedListCounts,
                    favoritesCount = FavoritesUtils.countPresent(favoriteIds, favoriteEligible),
                )
            }
        }
        filterJob = job
        return job
    }

    /**
     * Compares the game name against the search query using an exact match
     * and then again using a normalized form with diacritics removed.
     */
    private fun matches(gameName: String, searchQuery:String): Boolean {
        return gameName.contains(searchQuery, ignoreCase = true) || gameName.unaccent().contains(searchQuery, ignoreCase = true)
    }

}
