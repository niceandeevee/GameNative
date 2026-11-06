package app.gamenative.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ConfigInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UFS
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.PathType
import app.gamenative.enums.ReleaseState
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.Paths
import java.util.EnumSet
import kotlin.io.path.exists

@RunWith(RobolectricTestRunner::class)
class SteamUtilsFileSearchTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var appDir: File
    private lateinit var db: PluviaDatabase
    private val testAppId = "STEAM_123456"
    private val steamAppId = 123456

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File.createTempFile("steam_utils_test_", null)
        tempDir.delete()
        tempDir.mkdirs()

        // Set up DownloadService paths
        DownloadService.populateDownloadService(context)
        File(SteamService.internalAppInstallPath).mkdirs()
        SteamService.externalAppInstallPath.takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }

        // Create app directory that SteamService.getAppDirPath will return
        appDir = File(SteamService.internalAppInstallPath, "123456")
        appDir.mkdirs()

        // Set up ImageFs for restoreOriginalExecutable
        val imageFs = ImageFs.find(context)
        val wineprefix = File(imageFs.wineprefix)
        wineprefix.mkdirs()
        val dosDevices = File(wineprefix, "dosdevices")
        dosDevices.mkdirs()
        File(dosDevices, "a:").mkdirs()

        // Set up container directory so ContainerManager can find it
        // This prevents getOrCreateContainer() from trying to create a new container (which needs zstd-jni)
        val homeDir = File(imageFs.rootDir, "home")
        homeDir.mkdirs()

        val containerDir = File(homeDir, "${ImageFs.USER}-${testAppId}")
        containerDir.mkdirs()

        // Create a minimal container config file
        val container = Container(testAppId)
        container.setRootDir(containerDir)
        container.name = "Test Container"
        // Set up drives the same way the app does for Steam games
        val defaultDrives = Container.DEFAULT_DRIVES
        val appDirPath = appDir.absolutePath
        val drive: Char = Container.getNextAvailableDriveLetter(defaultDrives)
        container.drives = "$defaultDrives$drive:$appDirPath"
        container.saveData()  // This creates the config file that ContainerManager will load

        // Set up in-memory database with SteamApp entry
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Insert test SteamApp so getAppDirPath() can find it
        val testApp = SteamApp(
            id = steamAppId,
            name = "Test Game",
            config = ConfigInfo(installDir = "123456"),  // This is what getAppDirName() will use
            type = AppType.game,
            osList = EnumSet.of(OS.windows),
            releaseState = ReleaseState.released,
        )
        runBlocking {
            db.steamAppDao().insert(testApp)
        }

        // Create a mock SteamService instance and set it as SteamService.instance
        // This allows getAppInfoOf() to find the test app
        val mockSteamService = mock<SteamService>()
        whenever(mockSteamService.appDao).thenReturn(db.steamAppDao())

        // Mock steamClient and steamID for userSteamId property
        val mockSteamClient = mock<`in`.dragonbra.javasteam.steam.steamclient.SteamClient>()
        val mockSteamID = mock<`in`.dragonbra.javasteam.types.SteamID>()
        whenever(mockSteamService.steamClient).thenReturn(mockSteamClient)
        whenever(mockSteamClient.steamID).thenReturn(mockSteamID)

        // Set the mock as SteamService.instance using reflection
        try {
            val instanceField = SteamService::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, mockSteamService)  // null because it's a static field
        } catch (e: Exception) {
            fail("Failed to set SteamService.instance: ${e.message}")
        }

        mockkObject(SteamUtils)
        every { SteamUtils.generateAchievementsFile(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(SteamUtils)
        // Clean up temp directory
        tempDir.deleteRecursively()
        appDir.deleteRecursively()
        // Close database
        db.close()
    }

    @Test
    fun putBackSteamDlls_findsAndRestoresOrigFiles() {
        // Create .orig backup files
        val orig32File = File(appDir, "steam_api.dll.orig")
        val orig64File = File(appDir, "steam_api64.dll.orig")
        orig32File.writeBytes("backup 32bit dll content".toByteArray())
        orig64File.writeBytes("backup 64bit dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration
        val restored32File = File(appDir, "steam_api.dll")
        val restored64File = File(appDir, "steam_api64.dll")
        assertTrue("Should restore 32-bit DLL", restored32File.exists())
        assertTrue("Should restore 64-bit DLL", restored64File.exists())
        assertEquals("32-bit DLL content should match backup",
            "backup 32bit dll content", restored32File.readText())
        assertEquals("64-bit DLL content should match backup",
            "backup 64bit dll content", restored64File.readText())
    }

    @Test
    fun putBackSteamDlls_findsOrigFilesInSubdirectories() {
        // Create .orig file in subdirectory
        val subDir = File(appDir, "bin")
        subDir.mkdirs()
        val origFile = File(subDir, "steam_api.dll.orig")
        origFile.writeBytes("backup dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration
        val restoredFile = File(subDir, "steam_api.dll")
        assertTrue("Should restore DLL in subdirectory", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "backup dll content", restoredFile.readText())
    }

    @Test
    fun putBackSteamDlls_respectsMaxDepth() {
        // Create directory structure deeper than max depth (5)
        var currentDir = appDir
        for (i in 1..12) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
        }

        // Create .orig file beyond max depth
        val deepOrigFile = File(currentDir, "steam_api.dll.orig")
        deepOrigFile.writeBytes("backup content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify file beyond max depth was NOT restored
        val restoredFile = File(currentDir, "steam_api.dll")
        assertFalse("Should NOT restore DLL beyond max depth", restoredFile.exists())
    }

    @Test
    fun putBackSteamDlls_handlesCaseInsensitiveMatching() {
        // Create .orig file with different case
        val origFile = File(appDir, "STEAM_API64.DLL.ORIG")
        origFile.writeBytes("backup content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify restoration (case-insensitive)
        val restoredFile = File(appDir, "steam_api64.dll")
        assertTrue("Should restore DLL with case-insensitive matching", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "backup content", restoredFile.readText())
    }

    @Test
    fun restoreOriginalExecutable_findsAndRestoresOriginalExe() {
        // Set up dosdevices path
        val imageFs = ImageFs.find(context)
        // A: maps to the game dir on device; the restore logic reads the game dir directly.
        val dosDevicesPath = appDir
        dosDevicesPath.mkdirs()

        // Create multiple .original.exe files in different folders
        val origExeFile = File(dosDevicesPath, "game.exe.original.exe")
        origExeFile.writeBytes("original exe content".toByteArray())
        val nestedDir = File(dosDevicesPath, "bin")
        nestedDir.mkdirs()
        val origExeFile2 = File(nestedDir, "game2.exe.original.exe")
        origExeFile2.writeBytes("original exe content 2".toByteArray())

        // Call the actual function
        SteamUtils.restoreOriginalExecutable(context, steamAppId)

        // Verify restoration
        val restoredFile = File(dosDevicesPath, "game.exe")
        assertTrue("Should restore exe to original location", restoredFile.exists())
        assertEquals("Restored content should match backup",
            "original exe content", restoredFile.readText())
        val restoredFile2 = File(nestedDir, "game2.exe")
        assertTrue("Should restore exe to original location in subdirectory", restoredFile2.exists())
        assertEquals("Restored content should match backup for second exe",
            "original exe content 2", restoredFile2.readText())
    }

    @Test
    fun restoreOriginalExecutable_respectsMaxDepth() {
        // Set up dosdevices path
        val imageFs = ImageFs.find(context)
        // A: maps to the game dir on device; the restore logic reads the game dir directly.
        val dosDevicesPath = appDir
        dosDevicesPath.mkdirs()

        // Create directory structure deeper than max depth (5)
        var currentDir = dosDevicesPath
        for (i in 1..12) {
            currentDir = File(currentDir, "level$i")
            currentDir.mkdirs()
        }

        // Create .original.exe file beyond max depth
        val deepOrigExeFile = File(currentDir, "game.exe.original.exe")
        deepOrigExeFile.writeBytes("original exe content".toByteArray())

        // Call the actual function
        SteamUtils.restoreOriginalExecutable(context, steamAppId)

        // Verify file beyond max depth was NOT restored
        val restoredFile = File(currentDir, "game.exe")
        assertFalse("Should NOT restore exe beyond max depth", restoredFile.exists())
    }

    @Test
    fun restoreOriginalExecutable_doesNotFailWhenNoBackupFound() {
        // Set up dosdevices path with no backup files
        val imageFs = ImageFs.find(context)
        // A: maps to the game dir on device; the restore logic reads the game dir directly.
        val dosDevicesPath = appDir
        dosDevicesPath.mkdirs()

        // Call the actual function - should not throw
        try {
            SteamUtils.restoreOriginalExecutable(context, steamAppId)
            // Test passes if no exception is thrown
            assertTrue("Should complete without error when no backup found", true)
        } catch (e: Exception) {
            fail("Should not throw exception when no backup found: ${e.message}")
        }
    }

    @Test
    fun putBackSteamDlls_handlesBoth32And64BitInSingleTraversal() {
        // Create both .orig files
        val orig32File = File(appDir, "steam_api.dll.orig")
        val orig64File = File(appDir, "steam_api64.dll.orig")
        orig32File.writeBytes("backup 32bit".toByteArray())
        orig64File.writeBytes("backup 64bit".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify both are restored in a single traversal
        val restored32File = File(appDir, "steam_api.dll")
        val restored64File = File(appDir, "steam_api64.dll")
        assertTrue("Should restore 32-bit DLL", restored32File.exists())
        assertTrue("Should restore 64-bit DLL", restored64File.exists())
    }

    @Test
    fun putBackSteamDlls_deletesExistingDllBeforeRestoring() {
        // Create .orig backup file
        val origFile = File(appDir, "steam_api.dll.orig")
        origFile.writeBytes("backup content".toByteArray())

        // Create existing DLL with different content
        val existingDll = File(appDir, "steam_api.dll")
        existingDll.writeBytes("old dll content".toByteArray())

        // Call the actual function
        SteamUtils.putBackSteamDlls(appDir.absolutePath)

        // Verify old DLL was deleted and replaced with backup
        assertTrue("DLL should exist after restoration", existingDll.exists())
        assertEquals("DLL should contain backup content, not old content",
            "backup content", existingDll.readText())
    }

    @Test
    fun walkTopDown_doesNotLeakFileDescriptors() {
        // Create a deep directory structure with many files
        for (i in 1..10) {
            val dir = File(appDir, "level$i")
            dir.mkdirs()
            for (j in 1..5) {
                File(dir, "file$j.txt").writeText("content")
            }
        }

        // Call putBackSteamDlls multiple times (which uses walkTopDown internally)
        repeat(100) {
            SteamUtils.putBackSteamDlls(appDir.absolutePath)
        }

        // If file descriptors were leaking, we'd hit "Too many open files" error
        // This test passes if no exception is thrown
        assertTrue("Should complete without file descriptor leak", true)
    }

    @Test
    fun testReplaceSteamClientDll_restoreSteamApi_sequence() = runBlocking {
        // Step 1: Initial Setup - Create fake steam app structure
        val originalDllContent = "original steam_api64.dll content"
        val binDir = File(appDir, "bin")
        binDir.mkdirs()
        val dllFile = File(binDir, "steam_api64.dll")
        dllFile.writeBytes(originalDllContent.toByteArray())

        // Create game.exe files
        val imageFs = ImageFs.find(context)
        // A: maps to the game dir on device; the restore logic reads the game dir directly.
        val dosDevicesPath = appDir
        dosDevicesPath.mkdirs()
        val gameExe = File(dosDevicesPath, "game.exe")
        val gameExeUnpacked = File(dosDevicesPath, "game.exe.unpacked.exe")
        val gameExeOriginal = File(dosDevicesPath, "game.exe.original.exe")
        gameExe.writeBytes("game.exe content".toByteArray())
        gameExeUnpacked.writeBytes("unpacked exe content".toByteArray())
        gameExeOriginal.writeBytes("original exe content".toByteArray())

        // Set up container structure with Steam directory
        val containerDir = File(imageFs.rootDir, "home/${ImageFs.USER}-${testAppId}")
        val steamDir = File(containerDir, ".wine/drive_c/Program Files (x86)/Steam")
        steamDir.mkdirs()

        // Set container executablePath so restoreUnpackedExecutable and restoreOriginalExecutable can work
        val container = ContainerUtils.getContainer(context, testAppId)
        container.executablePath = "game.exe"
        container.saveData()

        // Ensure no markers exist
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)

        // Create a minimal steamclient.dll file to satisfy the ensureSteamSettings call
        val steamClientDll = File(steamDir, "steamclient.dll")
        steamClientDll.writeBytes("fake steamclient.dll".toByteArray())

        // Create steam client files in wineprefix Steam directory for backup/restore testing
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()
        val steamClientFiles = SteamUtils.steamClientFiles()
        val originalSteamClientContents = mutableMapOf<String, String>()
        steamClientFiles.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            val content = "original $fileName content"
            file.writeBytes(content.toByteArray())
            originalSteamClientContents[fileName] = content
        }

        // Create extra_dlls directory to test deletion
        val extraDllsDir = File(wineprefixSteamDir, "extra_dlls")
        extraDllsDir.mkdirs()
        File(extraDllsDir, "test.dll").writeBytes("test dll content".toByteArray())

        // Step 2: Call replaceSteamClientDll (First Time)
        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify steam client files are backed up
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        assertTrue("steamclient_backup directory should exist", backupDir.exists())
        steamClientFiles.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            assertTrue("Backup file $fileName.orig should exist", backupFile.exists())
            assertEquals("Backup file $fileName.orig should contain original content",
                originalSteamClientContents[fileName], backupFile.readText())
        }

        // Modify original files to verify they get restored
        steamClientFiles.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            if (file.exists()) {
                file.writeBytes("modified $fileName content".toByteArray())
            }
        }

        // Verify steam_settings folder is created next to steamclient.dll in Steam directory
        val steamSettingsDir = File(steamDir, "steam_settings")
        assertTrue("steam_settings folder should exist in Steam directory", steamSettingsDir.exists())

        // Verify config files exist
        val configsUserIni = File(steamSettingsDir, "configs.user.ini")
        val configsAppIni = File(steamSettingsDir, "configs.app.ini")
        val configsMainIni = File(steamSettingsDir, "configs.main.ini")
        assertTrue("configs.user.ini should exist", configsUserIni.exists())
        assertTrue("configs.app.ini should exist", configsAppIni.exists())
        assertTrue("configs.main.ini should exist", configsMainIni.exists())

        // Verify steam_appid.txt exists in steam_settings folder
        val steamAppIdFile = File(steamSettingsDir, "steam_appid.txt")
        assertTrue("steam_appid.txt should exist in steam_settings folder", steamAppIdFile.exists())
        assertEquals("steam_appid.txt should contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify steam_api64.dll in app directory is NOT replaced (remains original)
        assertEquals("steam_api64.dll should remain original after replaceSteamClientDll",
            originalDllContent, dllFile.readText())

        // Verify game.exe is NOT overwritten after first replaceSteamClientDll call
        assertEquals("game.exe should not be overwritten after replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))

        // Step 3: Call restoreSteamApi
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)

        SteamUtils.restoreSteamApi(context, testAppId)

        // Verify restoreOriginalExecutable overwrites game.exe with game.exe.original.exe content
        assertEquals("game.exe should be overwritten with game.exe.original.exe content after restoreSteamApi",
            "original exe content", gameExe.readText())

        // Verify steam_api64.dll remains the same (not replaced, since restoreSteamApi calls putBackSteamDlls
        // which only restores from .orig if it exists, and we don't have a .orig file at this point)
        assertEquals("steam_api64.dll should remain the same after restoreSteamApi",
            originalDllContent, dllFile.readText())

        // Verify steam client files are restored from backup
        steamClientFiles.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            assertTrue("Steam client file $fileName should exist after restore", file.exists())
            assertEquals("Steam client file $fileName should be restored to original content",
                originalSteamClientContents[fileName], file.readText())
        }

        // Verify extra_dlls directory is deleted
        assertFalse("extra_dlls directory should be deleted after restoreSteamclientFiles",
            extraDllsDir.exists())

        // Verify marker was set
        assertTrue("Should add STEAM_DLL_RESTORED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED))

        // Step 4: Call replaceSteamClientDll (Second Time)
        // Remove markers to allow the function to run
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED)
        MarkerUtils.removeMarker(appDir.absolutePath, Marker.STEAM_DLL_RESTORED)

        SteamUtils.replaceSteamclientDll(context, testAppId)

        // Verify restoreUnpackedExecutable overwrites game.exe with game.exe.unpacked.exe content
        assertEquals("game.exe should be overwritten with game.exe.unpacked.exe content after second replaceSteamClientDll",
            "unpacked exe content", gameExe.readText())

        // Verify steam_settings folder still exists next to steamclient.dll in Steam directory
        assertTrue("steam_settings folder should still exist in Steam directory",
            steamSettingsDir.exists())

        // Verify config files still exist in Steam directory
        assertTrue("configs.user.ini should still exist in Steam directory", configsUserIni.exists())
        assertTrue("configs.app.ini should still exist in Steam directory", configsAppIni.exists())
        assertTrue("configs.main.ini should still exist in Steam directory", configsMainIni.exists())

        // Verify configs.user.ini contains all required fields
        val userIniContent = configsUserIni.readText()
        assertTrue("configs.user.ini should contain [user::general] section", userIniContent.contains("[user::general]"))
        assertTrue("configs.user.ini should contain account_name field", userIniContent.contains("account_name="))
        assertTrue("configs.user.ini should contain account_steamid field", userIniContent.contains("account_steamid="))
        assertTrue("configs.user.ini should contain language field", userIniContent.contains("language="))
        assertFalse("configs.user.ini should not contain ticket field", userIniContent.contains("ticket="))

        // Verify configs.app.ini contains expected content
        val appIniContent = configsAppIni.readText()
        assertTrue("configs.app.ini should contain [app::dlcs] section", appIniContent.contains("[app::dlcs]"))
        assertTrue("configs.app.ini should contain unlock_all field", appIniContent.contains("unlock_all="))

        // Verify configs.main.ini contains expected content
        val mainIniContent = configsMainIni.readText()
        assertTrue("configs.main.ini should contain [main::connectivity] section", mainIniContent.contains("[main::connectivity]"))
        assertTrue("configs.main.ini should contain disable_lan_only=1", mainIniContent.contains("disable_lan_only=1"))

        // Verify steam_appid.txt still exists and has correct content
        assertTrue("steam_appid.txt should still exist in Steam directory steam_settings folder",
            steamAppIdFile.exists())
        assertEquals("steam_appid.txt should still contain correct app ID",
            steamAppId.toString(), steamAppIdFile.readText().trim())

        // Verify steam_api64.dll still remains original (putBackSteamDlls doesn't change it if no .orig exists)
        assertEquals("steam_api64.dll should still remain original",
            originalDllContent, dllFile.readText())

        // Verify marker was set
        assertTrue("Should add STEAM_COLDCLIENT_USED marker",
            MarkerUtils.hasMarker(appDir.absolutePath, Marker.STEAM_COLDCLIENT_USED))
    }

    @Test
    fun test_backupSteamclientFiles_backsUpExistingFiles() {
        val imageFs = ImageFs.find(context)
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()

        // Create some (not all) of the steam client files
        val steamClientFiles = SteamUtils.steamClientFiles()
        val filesToCreate = steamClientFiles.take(3) // Create only first 3 files
        val originalContents = mutableMapOf<String, String>()

        filesToCreate.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            val content = "original $fileName content"
            file.writeBytes(content.toByteArray())
            originalContents[fileName] = content
        }

        // Call backupSteamclientFiles
        SteamUtils.backupSteamclientFiles(context, steamAppId)

        // Verify backup directory is created
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        assertTrue("steamclient_backup directory should exist", backupDir.exists())

        // Verify only existing files are backed up
        filesToCreate.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            assertTrue("Backup file $fileName.orig should exist", backupFile.exists())
            assertEquals("Backup file $fileName.orig should contain original content",
                originalContents[fileName], backupFile.readText())
        }

        // Verify non-existent files are NOT backed up
        val filesNotCreated = steamClientFiles.drop(3)
        filesNotCreated.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            assertFalse("Backup file $fileName.orig should NOT exist for non-existent file", backupFile.exists())
        }
    }

    @Test
    fun test_backupSteamclientFiles_handlesNonExistentFiles() {
        val imageFs = ImageFs.find(context)
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()

        // Create only some of the steam client files
        val steamClientFiles = SteamUtils.steamClientFiles()
        val filesToCreate = listOf(steamClientFiles[0], steamClientFiles[2], steamClientFiles[4]) // Create 3 specific files
        val originalContents = mutableMapOf<String, String>()

        filesToCreate.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            val content = "original $fileName content"
            file.writeBytes(content.toByteArray())
            originalContents[fileName] = content
        }

        // Call backupSteamclientFiles
        SteamUtils.backupSteamclientFiles(context, steamAppId)

        // Verify backup directory is created
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        assertTrue("steamclient_backup directory should exist", backupDir.exists())

        // Verify existing files are backed up
        filesToCreate.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            assertTrue("Backup file $fileName.orig should exist", backupFile.exists())
            assertEquals("Backup file $fileName.orig should contain original content",
                originalContents[fileName], backupFile.readText())
        }
    }

    @Test
    fun test_restoreSteamclientFiles_restoresFromBackup() {
        val imageFs = ImageFs.find(context)
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()

        // Create backup files
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        backupDir.mkdirs()
        val steamClientFiles = SteamUtils.steamClientFiles()
        val backupContents = mutableMapOf<String, String>()

        steamClientFiles.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            val content = "backup $fileName content"
            backupFile.writeBytes(content.toByteArray())
            backupContents[fileName] = content
        }

        // Modify or delete original files
        steamClientFiles.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            if (fileName == steamClientFiles[0]) {
                // Delete first file
                if (file.exists()) file.delete()
            } else {
                // Modify other files
                file.writeBytes("modified $fileName content".toByteArray())
            }
        }

        // Call restoreSteamclientFiles
        SteamUtils.restoreSteamclientFiles(context, steamAppId)

        // Verify files are restored from backup
        steamClientFiles.forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            assertTrue("Steam client file $fileName should exist after restore", file.exists())
            assertEquals("Steam client file $fileName should be restored from backup",
                backupContents[fileName], file.readText())
        }
    }

    @Test
    fun test_restoreSteamclientFiles_deletesExtraDlls() {
        val imageFs = ImageFs.find(context)
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()

        // Create backup files
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        backupDir.mkdirs()
        val steamClientFiles = SteamUtils.steamClientFiles()
        steamClientFiles.forEach { fileName ->
            val backupFile = File(backupDir, "$fileName.orig")
            backupFile.writeBytes("backup content".toByteArray())
        }

        // Create extra_dlls directory with files
        val extraDllsDir = File(wineprefixSteamDir, "extra_dlls")
        extraDllsDir.mkdirs()
        val testDll = File(extraDllsDir, "test.dll")
        testDll.writeBytes("test dll content".toByteArray())
        val testDll2 = File(extraDllsDir, "test2.dll")
        testDll2.writeBytes("test2 dll content".toByteArray())

        assertTrue("extra_dlls directory should exist before restore", extraDllsDir.exists())

        // Call restoreSteamclientFiles
        SteamUtils.restoreSteamclientFiles(context, steamAppId)

        // Verify extra_dlls directory is deleted
        assertFalse("extra_dlls directory should be deleted after restoreSteamclientFiles",
            extraDllsDir.exists())
    }

    @Test
    fun test_restoreSteamclientFiles_handlesMissingBackup() {
        val imageFs = ImageFs.find(context)
        val wineprefixSteamDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
        wineprefixSteamDir.mkdirs()

        // Create some original files
        val steamClientFiles = SteamUtils.steamClientFiles()
        val originalContents = mutableMapOf<String, String>()
        steamClientFiles.take(2).forEach { fileName ->
            val file = File(wineprefixSteamDir, fileName)
            val content = "original $fileName content"
            file.writeBytes(content.toByteArray())
            originalContents[fileName] = content
        }

        // Ensure no backup directory exists
        val backupDir = File(wineprefixSteamDir, "steamclient_backup")
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }

        // Call restoreSteamclientFiles - should not throw
        try {
            SteamUtils.restoreSteamclientFiles(context, steamAppId)
            // Test passes if no exception is thrown
            assertTrue("Should complete without error when backup missing", true)
        } catch (e: Exception) {
            fail("Should not throw exception when backup missing: ${e.message}")
        }

        // Verify original files remain unchanged
        originalContents.forEach { (fileName, content) ->
            val file = File(wineprefixSteamDir, fileName)
            assertTrue("Original file $fileName should still exist", file.exists())
            assertEquals("Original file $fileName should remain unchanged",
                content, file.readText())
        }
    }
}
