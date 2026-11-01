package app.gamenative.ui.enums

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.R

enum class LibraryTab(
    @get:StringRes val labelResId: Int,
    val showCustom: Boolean,
    val showSteam: Boolean,
    val installedOnly: Boolean,
    val icon: ImageVector? = null,
) {
    ALL(
        labelResId = R.string.tab_all,
        showCustom = true,
        showSteam = true,
        installedOnly = false,
    ),
    FAVORITES(
        labelResId = R.string.tab_favorites,
        showCustom = true,
        showSteam = true,
        installedOnly = false,
        icon = Icons.Rounded.Star,
    ),
    STEAM(
        labelResId = R.string.tab_steam,
        showCustom = false,
        showSteam = true,
        installedOnly = false,
    ),
    LOCAL(
        labelResId = R.string.tab_local,
        showCustom = true,
        showSteam = false,
        installedOnly = false,
    ),
    ;

    companion object {
        val configurableEntries = listOf(STEAM)

        /**
         * Tabs shown in the UI. Custom (LOCAL) games work on all flavors: legacy maps folders
         * in place via all-files access, modern imports them into app-owned storage.
         */
        val visibleEntries: List<LibraryTab>
            get() = entries

        fun normalizeVisibleTabs(
            serialized: String,
            supportedTabs: List<LibraryTab> = visibleEntries,
        ): List<LibraryTab> {
            val supported = supportedTabs.distinct()
            if (serialized.isBlank()) return supported

            if (!serialized.startsWith(VISIBLE_TABS_PREFIX)) {
                val hiddenTabs = serialized
                    .split(',')
                    .map { it.trim() }
                    .filter { it.startsWith(HIDDEN_PREFIX) }
                    .map { it.removePrefix(HIDDEN_PREFIX) }
                    .toSet()
                return supported.filter { it !in configurableEntries || it.name !in hiddenTabs }
            }

            val selected = serialized
                .removePrefix(VISIBLE_TABS_PREFIX)
                .split(',')
                .mapNotNull { token ->
                    val value = token.trim()
                    entries.firstOrNull { it.name == value }
                }
                .toSet()

            return supported.filter { it !in configurableEntries || it in selected }
        }

        fun serializeVisibleTabs(tabs: List<LibraryTab>): String =
            tabs.distinct().joinToString(",", prefix = VISIBLE_TABS_PREFIX) { it.name }

        fun LibraryTab.next(visibleTabs: List<LibraryTab> = visibleEntries): LibraryTab {
            val values = visibleTabs.ifEmpty { listOf(ALL) }
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[(index + 1) % values.size]
        }

        fun LibraryTab.previous(visibleTabs: List<LibraryTab> = visibleEntries): LibraryTab {
            val values = visibleTabs.ifEmpty { listOf(ALL) }
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[if (index == 0) values.size - 1 else index - 1]
        }

        private const val HIDDEN_PREFIX = "!"
        private const val VISIBLE_TABS_PREFIX = "v2:"
    }
}
