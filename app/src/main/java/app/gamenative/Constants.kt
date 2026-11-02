package app.gamenative

/**
 * Constants values that may be used around the app more than once.
 * Constants that are used in composables and or view models should be here too.
 */
object Constants {

    object XServer {
        const val DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme,loaddll"
    }

    object Persona {
        const val AVATAR_BASE_URL = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/avatars/"
        const val MISSING_AVATAR_URL = "${AVATAR_BASE_URL}fe/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg"
    }

    object Library {
        const val ICON_URL = "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/"
        const val STORE_URL = "https://store.steampowered.com/app/"
    }

    object Misc {
        const val GITHUB_LINK = "https://github.com/niceandeevee/GameNative"
    }
}
