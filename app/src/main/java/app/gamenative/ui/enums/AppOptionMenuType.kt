package app.gamenative.ui.enums

import androidx.annotation.StringRes
import app.gamenative.R

enum class AppOptionMenuType(@StringRes val title: Int) {
    StorePage(R.string.option_open_store_page),
    CreateShortcut(R.string.create_shortcut),
    ExportFrontend(R.string.option_export_for_frontend),
    CopyLaunchLink(R.string.option_copy_launch_link),
    RunContainer(R.string.option_open_container),
    EditContainer(R.string.option_edit_container),
    ResetToDefaults(R.string.option_reset_to_defaults),
    ResetDrm(R.string.option_reset_drm),
    ImportConfig(R.string.import_config),
    ExportConfig(R.string.export_config),
    ImportSaves(R.string.option_import_saves),
    ExportSaves(R.string.option_export_saves),
    Uninstall(R.string.uninstall),
    VerifyFiles(R.string.option_verify_files),
    Update(R.string.option_update),
    MoveToExternalStorage(R.string.option_move_to_external_storage),
    MoveToInternalStorage(R.string.option_move_to_internal_storage),
    ForceCloudSync(R.string.option_force_cloud_sync),
    BrowseOnlineSaves(R.string.option_browse_online_saves),
    ForceDownloadRemote(R.string.option_force_download_remote),
    ForceUploadLocal(R.string.option_force_upload_local),
    TestGraphics(R.string.option_test_graphics),
    PlayWithDiagnostics(R.string.option_play_with_diagnostics),
    ShareDiagnostics(R.string.option_share_diagnostics),
    ManageGameContent(R.string.option_manage_dlc),
    ImportFiles(R.string.option_import_files),
    ExportFiles(R.string.option_export_files),
    ManageMods(R.string.option_manage_mods),
    ChangeBranch(R.string.change_branch),
    ChangePreferredCopy(R.string.change_preferred_copy),
    AddToFavorites(R.string.option_add_to_favorites),
    RemoveFromFavorites(R.string.option_remove_from_favorites),
}
