package app.gamenative.service.download

/**
 * Loads `libgndownload.so` — the single Rust library (`cpp/gn-download/rust`) behind
 * [GameDownloadService]: the Steam CDN depot downloader and the tree deleter.
 */
object GameDownloadNative {

    private const val LIB_NAME = "gndownload"

    @Volatile
    private var loaded = false

    @Synchronized
    @JvmStatic
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary(LIB_NAME)
        loaded = true
    }

    @JvmStatic
    fun isLoaded(): Boolean = loaded
}
