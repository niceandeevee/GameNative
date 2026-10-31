package app.gamenative

/**
 * Stores this build supports. Only Steam: Epic and GOG are switched off, so their services never
 * start or sync and their library tabs and logins are hidden.
 */
object StoreSupport {
    const val EPIC_ENABLED = false
    const val GOG_ENABLED = false
}
