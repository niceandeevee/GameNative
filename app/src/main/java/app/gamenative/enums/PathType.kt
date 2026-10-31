package app.gamenative.enums

import android.content.Context
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.nio.file.Paths
import timber.log.Timber

enum class PathType {
    GameInstall,
    SteamUserData,
    WinMyDocuments,
    WinAppDataLocal,
    WinAppDataLocalLow,
    WinAppDataRoaming,
    WinSavedGames,
    WinProgramData,
    LinuxHome,
    LinuxXdgDataHome,
    LinuxXdgConfigHome,
    MacHome,
    MacAppSupport,
    None,
    Root,
    ;

    /**
     * Turns a path type to a full path through the android system to the expected directory in
     * the wine prefix or the steam common app dir.
     */
    fun toAbsPath(container: Container, appId: Int, accountId: Long): String {
        val root = container.rootDir.absolutePath
        val path = when (this) {
            GameInstall -> SteamService.getAppDirPath(appId)
            SteamUserData -> Paths.get(
                root,
                ".wine/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId/remote",
            ).toString()
            WinMyDocuments -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "Documents/",
            ).toString()
            WinAppDataLocal -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "AppData/Local/",
            ).toString()
            WinAppDataLocalLow -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "AppData/LocalLow/",
            ).toString()
            WinAppDataRoaming -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "AppData/Roaming/",
            ).toString()
            WinSavedGames -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "Saved Games/",
            ).toString()
            WinProgramData -> Paths.get(
                root,
                ".wine/drive_c/ProgramData/",
            ).toString()
            Root -> Paths.get(
                root,
                ".wine/drive_c/users",
                ImageFs.USER,
                "",
            ).toString()
            else -> {
                Timber.e("Did not recognize or unsupported path type $this")
                SteamService.getAppDirPath(appId)
            }
        }
        return if (!path.endsWith("/")) "$path/" else path
    }

    val isWindows: Boolean
        get() = when (this) {
            GameInstall,
            SteamUserData,
            WinMyDocuments,
            WinAppDataLocal,
            WinAppDataLocalLow,
            WinAppDataRoaming,
            WinSavedGames,
            WinProgramData,
            Root,
            -> true
            else -> false
        }

    companion object {
        val DEFAULT = SteamUserData


        fun from(keyValue: String?): PathType {
            return when (keyValue?.lowercase()) {
                "%${GameInstall.name.lowercase()}%",
                GameInstall.name.lowercase(),
                -> GameInstall
                "%${SteamUserData.name.lowercase()}%",
                SteamUserData.name.lowercase(),
                "steamuserbasestorage",
                "%steamuserbasestorage%",
                -> SteamUserData
                "%${WinMyDocuments.name.lowercase()}%",
                WinMyDocuments.name.lowercase(),
                "steamclouddocuments",
                "%steamclouddocuments%",
                -> WinMyDocuments
                "%${WinAppDataLocal.name.lowercase()}%",
                WinAppDataLocal.name.lowercase(),
                -> WinAppDataLocal
                "%${WinAppDataLocalLow.name.lowercase()}%",
                WinAppDataLocalLow.name.lowercase(),
                -> WinAppDataLocalLow
                "%${WinAppDataRoaming.name.lowercase()}%",
                WinAppDataRoaming.name.lowercase(),
                -> WinAppDataRoaming
                "%${WinSavedGames.name.lowercase()}%",
                WinSavedGames.name.lowercase(),
                -> WinSavedGames
                "%${WinProgramData.name.lowercase()}%",
                WinProgramData.name.lowercase(),
                -> WinProgramData
                "%${LinuxHome.name.lowercase()}%",
                LinuxHome.name.lowercase(),
                -> LinuxHome
                "%${LinuxXdgDataHome.name.lowercase()}%",
                LinuxXdgDataHome.name.lowercase(),
                -> LinuxXdgDataHome
                "%${LinuxXdgConfigHome.name.lowercase()}%",
                LinuxXdgConfigHome.name.lowercase(),
                -> LinuxXdgConfigHome
                "%${MacHome.name.lowercase()}%",
                MacHome.name.lowercase(),
                -> MacHome
                "%${MacAppSupport.name.lowercase()}%",
                MacAppSupport.name.lowercase(),
                -> MacAppSupport
                "%${Root.name.lowercase()}%",
                Root.name.lowercase(),
                "windowshome",
                "%windowshome%",
                "%root_mod%",
                "root_mod",
                -> Root
                else -> {
                    if (keyValue != null) {
                        Timber.w("Could not identify $keyValue as PathType")
                    }
                    None
                }
            }
        }
    }
}
