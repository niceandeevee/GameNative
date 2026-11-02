package app.gamenative.ui.component.dialog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.settings.SettingsListDropdown
import com.alorma.compose.settings.ui.SettingsSwitch
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.DefaultVersion
import com.winlator.core.KeyValueSet
import com.winlator.core.StringUtils
import com.winlator.contents.ContentProfile
import com.winlator.xenvironment.components.PulseAudioComponent
import java.util.Locale

@Composable
fun GeneralTabContent(
    state: ContainerConfigState,
    nonzeroResolutionError: String,
) {
    val config = state.config.value

    // Microphone input is opt-in. The RECORD_AUDIO runtime permission is only requested when
    // the user turns the toggle on (never at screen entry), and only when it isn't already
    // granted. The grant state is checked fresh at toggle time rather than cached, so a
    // permission revoked in system settings is picked up correctly.
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Only enable the option once the user actually granted access.
        if (granted) {
            state.config.value = state.config.value.copy(micEnabled = true)
        }
    }

    val graphicsDrivers = state.graphicsDrivers.value
    val suspendBehaviorEntries = listOf(
        stringResource(R.string.suspend_behavior_manual),
        stringResource(R.string.suspend_behavior_auto),
        stringResource(R.string.suspend_behavior_never),
    )
    val suspendBehaviorIndex = when {
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true) -> 0
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true) -> 2
        else -> 1
    }

    if (state.showCustomResolutionDialog.value) {
        AlertDialog(
            onDismissRequest = { state.showCustomResolutionDialog.value = false },
            title = { Text(text = stringResource(R.string.container_config_custom_resolution_title)) },
            text = {
                val heightFocusRequester = remember { FocusRequester() }
                Column {
                    Row {
                        NoExtractOutlinedTextField(
                            modifier = Modifier.width(128.dp),
                            value = state.customScreenWidth.value,
                            onValueChange = { state.customScreenWidth.value = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { heightFocusRequester.requestFocus() }),
                            label = { Text(text = stringResource(R.string.width)) },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            text = stringResource(R.string.container_config_custom_resolution_separator),
                            style = TextStyle(fontSize = 16.sp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        NoExtractOutlinedTextField(
                            modifier = Modifier.width(128.dp).focusRequester(heightFocusRequester),
                            value = state.customScreenHeight.value,
                            onValueChange = { state.customScreenHeight.value = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(text = stringResource(R.string.height)) },
                            singleLine = true,
                        )
                    }
                    if (state.customResolutionValidationError.value != null) {
                        Text(
                            text = state.customResolutionValidationError.value!!,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            style = TextStyle(fontSize = 16.sp),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val widthInt = state.customScreenWidth.value.toIntOrNull() ?: 0
                        val heightInt = state.customScreenHeight.value.toIntOrNull() ?: 0
                        if (widthInt == 0 || heightInt == 0) {
                            state.customResolutionValidationError.value = nonzeroResolutionError
                        } else {
                            state.customResolutionValidationError.value = null
                            state.applyScreenSizeToConfig()
                            state.showCustomResolutionDialog.value = false
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { state.showCustomResolutionDialog.value = false },
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    SettingsGroup() {
        run {
            val wineIndex = state.bionicWineOptions.ids.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.wine_version)) },
                value = wineIndex,
                items = state.bionicWineOptions.labels,
                itemMuted = state.bionicWineOptions.muted,
                onItemSelected = { idx ->
                    val selectedId = state.bionicWineOptions.ids.getOrNull(idx).orEmpty()
                    val isManifestNotInstalled = state.bionicWineOptions.muted.getOrNull(idx) == true
                    val manifestEntry = state.bionicWineManifestById[selectedId]
                    if (isManifestNotInstalled && manifestEntry != null) {
                        val expectedType = if (selectedId.startsWith("proton", true)) {
                            ContentProfile.ContentType.CONTENT_TYPE_PROTON
                        } else {
                            ContentProfile.ContentType.CONTENT_TYPE_WINE
                        }
                        state.launchManifestContentInstall(manifestEntry, expectedType) {
                            state.config.value = config.copy(wineVersion = selectedId)
                        }
                        return@SettingsListDropdown
                    }
                    state.config.value = config.copy(wineVersion = selectedId.ifEmpty { state.bionicWineOptions.labels[idx] })
                },
            )
        }
        ExecutablePathDropdown(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            value = config.executablePath,
            onValueChange = { state.config.value = config.copy(executablePath = it) },
            containerData = config,
        )
        NoExtractOutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            value = config.execArgs,
            onValueChange = { state.config.value = config.copy(execArgs = it) },
            label = { Text(text = stringResource(R.string.exec_arguments)) },
            placeholder = { Text(text = stringResource(R.string.exec_arguments_example)) },
            singleLine = true,
        )
        val displayNameForLanguage: (String) -> String = { code ->
            when (code) {
                "schinese" -> "Simplified Chinese"
                "tchinese" -> "Traditional Chinese"
                "koreana" -> "Korean"
                "latam" -> "Spanish (Latin America)"
                "brazilian" -> "Portuguese (Brazil)"
                else -> code.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
            }
        }
        SettingsListDropdown(
            enabled = true,
            value = state.languageIndex.value,
            items = state.languages.map(displayNameForLanguage),
            fallbackDisplay = displayNameForLanguage("english"),
            onItemSelected = { index ->
                state.languageIndex.value = index
                state.config.value = config.copy(language = state.languages[index])
            },
            title = { Text(text = stringResource(R.string.language)) },
            colors = settingsTileColors(),
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.screen_size)) },
            value = state.screenSizeIndex.value,
            items = state.screenSizes,
            onItemSelected = {
                state.screenSizeIndex.value = it
                if (it == 0) {
                    state.showCustomResolutionDialog.value = true
                } else {
                    state.applyScreenSizeToConfig()
                }
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.portrait_mode)) },
            subtitle = { Text(text = stringResource(R.string.portrait_mode_description)) },
            state = config.portraitMode,
            onCheckedChange = { state.config.value = config.copy(portraitMode = it) },
        )
        if (config.portraitMode && displayHasCutout(LocalView.current)) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.portrait_below_cutout)) },
                subtitle = { Text(text = stringResource(R.string.portrait_below_cutout_description)) },
                state = config.portraitBelowCutout,
                onCheckedChange = { state.config.value = config.copy(portraitBelowCutout = it) },
            )
        }
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.audio_driver)) },
            value = state.audioDriverIndex.value,
            items = state.audioDrivers,
            onItemSelected = {
                state.audioDriverIndex.value = it
                state.config.value = config.copy(audioDriver = StringUtils.parseIdentifier(state.audioDrivers[it]))
            },
        )
        if (config.audioDriver == "pulseaudio") {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.pulseaudio_low_latency)) },
                state = config.pulseaudioLowLatency,
                onCheckedChange = { state.config.value = config.copy(pulseaudioLowLatency = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.microphone_input)) },
            subtitle = { Text(text = stringResource(R.string.microphone_input_description)) },
            state = config.micEnabled,
            onCheckedChange = { enabled ->
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (enabled && !hasMicPermission) {
                    // Ask only now that the user explicitly wants mic input; the launcher
                    // callback flips the switch on if they grant.
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    state.config.value = config.copy(micEnabled = enabled)
                }
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.force_dlc)) },
            subtitle = { Text(text = stringResource(R.string.force_dlc_description)) },
            state = config.forceDlc,
            onCheckedChange = { state.config.value = config.copy(forceDlc = it) },
        )

        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.use_legacy_drm)) },
            state = config.useLegacyDRM,
            onCheckedChange = { state.config.value = config.copy(useLegacyDRM = it) },
        )
        if (!config.useLegacyDRM) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.unpack_files)) },
                subtitle = { Text(text = stringResource(R.string.unpack_files_description)) },
                state = config.unpackFiles,
                onCheckedChange = { state.config.value = config.copy(unpackFiles = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.load_mods)) },
            subtitle = { Text(text = AnnotatedString.fromHtml(stringResource(R.string.load_mods_description))) },
            state = config.loadMods,
            onCheckedChange = { state.config.value = config.copy(loadMods = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.steam_offline_mode)) },
            subtitle = { Text(text = stringResource(R.string.steam_offline_mode_description)) },
            state = config.steamOfflineMode,
            onCheckedChange = { state.config.value = config.copy(steamOfflineMode = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.launch_steam_client_beta)) },
            subtitle = { Text(text = stringResource(R.string.launch_steam_client_description)) },
            state = config.launchRealSteam,
            onCheckedChange = {
                state.config.value = if (it) {
                    config.copy(launchRealSteam = true, launchBionicSteam = false)
                } else {
                    config.copy(launchRealSteam = false)
                }
            },
        )
        if (config.launchRealSteam) {
            val steamTypeItems = listOf("Headless", "Normal", "Light", "Ultra Light")
            val currentSteamTypeIndex = when (config.steamType.lowercase()) {
                Container.STEAM_TYPE_HEADLESS -> 0
                Container.STEAM_TYPE_LIGHT -> 2
                Container.STEAM_TYPE_ULTRALIGHT -> 3
                else -> 1
            }
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.steam_type)) },
                value = currentSteamTypeIndex,
                items = steamTypeItems,
                onItemSelected = {
                    val type = when (it) {
                        0 -> Container.STEAM_TYPE_HEADLESS
                        2 -> Container.STEAM_TYPE_LIGHT
                        3 -> Container.STEAM_TYPE_ULTRALIGHT
                        else -> Container.STEAM_TYPE_NORMAL
                    }
                    state.config.value = config.copy(steamType = type)
                },
            )
        }
        if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.launch_bionic_steam)) },
                subtitle = { Text(text = stringResource(R.string.launch_bionic_steam_description)) },
                state = config.launchBionicSteam,
                onCheckedChange = {
                    state.config.value = if (it) {
                        config.copy(launchBionicSteam = true, launchRealSteam = false)
                    } else {
                        config.copy(launchBionicSteam = false)
                    }
                },
            )
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.disable_libredirect_title)) },
                subtitle = { Text(text = stringResource(R.string.disable_libredirect_subtitle)) },
                state = config.disableLibredirect,
                onCheckedChange = {
                    state.config.value = if (it) {
                        config.copy(disableLibredirect = true, fasterExternalLoading = false)
                    } else {
                        config.copy(disableLibredirect = false)
                    }
                },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            enabled = !config.disableLibredirect,
            title = { Text(text = stringResource(R.string.faster_external_loading_title)) },
            subtitle = { Text(text = stringResource(R.string.faster_external_loading_subtitle)) },
            state = config.fasterExternalLoading,
            onCheckedChange = { state.config.value = config.copy(fasterExternalLoading = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.suspend_behavior)) },
            value = suspendBehaviorIndex,
            items = suspendBehaviorEntries,
            onItemSelected = { index ->
                val policy = when (index) {
                    0 -> Container.SUSPEND_POLICY_MANUAL
                    1 -> Container.SUSPEND_POLICY_AUTO
                    2 -> Container.SUSPEND_POLICY_NEVER
                    else -> Container.SUSPEND_POLICY_MANUAL
                }
                state.config.value = config.copy(suspendPolicy = policy)
            },
        )
    }
}

private fun displayHasCutout(view: View): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return view.display?.cutout != null
}
