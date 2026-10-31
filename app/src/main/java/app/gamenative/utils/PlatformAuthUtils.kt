package app.gamenative.utils

import android.content.Context
import app.gamenative.service.SteamService

object PlatformAuthUtils {
    /** Steam is the only store this build supports. */
    @Suppress("UNUSED_PARAMETER")
    fun isSignedInToAnyPlatform(context: Context): Boolean = SteamService.isLoggedIn
}
