/**
 * Compose 悬浮球设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brycewg.asrkb.host.BibiPermissionType
import com.brycewg.asrkb.host.PermissionRouter
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.floating.FloatingServiceManager
import com.brycewg.asrkb.ui.floating.floatingAsrNeedsAccessibility as policyFloatingAsrNeedsAccessibility
import com.brycewg.asrkb.ui.floating.floatingInputNeedsAccessibility as policyFloatingInputNeedsAccessibility
import com.brycewg.asrkb.ui.floatingball.ResultDisplayMode
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.wake.WakeServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FLOATING_TAG = "FloatingSettingsScreen"

private data class FloatingPackageEdits(
    val paste: String,
    val pasteChanged: Boolean
)

/**
 * 悬浮球设置路由包装：内部承载「唤醒词管理」二级页。
 */
@Composable
internal fun FloatingSettingsRoute(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    var showWakeManager by remember { mutableStateOf(false) }
    if (showWakeManager) {
        WakeWordManagerScreen(uiMode = uiMode, onBack = { showWakeManager = false })
    } else {
        FloatingSettingsScreen(
            uiMode = uiMode,
            onBack = onBack,
            onOpenWakeManager = { showWakeManager = true },
            actions = actions
        )
    }
}

private class FloatingPackagePersistState {
    var paste: String? = null
}

@Composable
fun FloatingSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenWakeManager: () -> Unit,
    actions: SettingsActionController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(appContext) { Prefs(appContext) }
    val serviceManager = remember(appContext) { FloatingServiceManager(appContext) }
    val scope = rememberCoroutineScope()
    var uiState by remember(appContext) { mutableStateOf(FloatingSettingsUiState.placeholder) }
    var pastePackages by remember(appContext) { mutableStateOf("") }
    val persistedPackages = remember(appContext) { FloatingPackagePersistState() }
    var settingsLoaded by remember(appContext) { mutableStateOf(false) }
    var pendingAsrEnable by remember { mutableStateOf(false) }
    var pendingAsrPermission by remember { mutableStateOf<FloatingPermissionRequest?>(null) }
    var pendingVolumeKeyEnable by remember { mutableStateOf(false) }
    var pendingShakeEnable by remember { mutableStateOf(false) }
    var autoAccessibilityRequested by remember { mutableStateOf(false) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    val latestPastePackages by rememberUpdatedState(pastePackages)
    val latestSettingsLoaded by rememberUpdatedState(settingsLoaded)

    // 语音唤醒服务实况：开关与副标题的数据源（见 wake_word 条目）
    val wakeServiceState by WakeServiceState.state.collectAsState()

    fun applySnapshot(snapshot: FloatingSettingsPrefsSnapshot) {
        if (uiState != snapshot.uiState) uiState = snapshot.uiState
        if (pastePackages != snapshot.pastePackages) pastePackages = snapshot.pastePackages
        persistedPackages.paste = snapshot.pastePackages
        if (!settingsLoaded) settingsLoaded = true
    }

    suspend fun loadSnapshot(): FloatingSettingsPrefsSnapshot = withContext(Dispatchers.IO) {
        FloatingSettingsPrefsSnapshot.fromPrefs(prefs)
    }

    suspend fun loadUiState(): FloatingSettingsUiState = withContext(Dispatchers.IO) {
        FloatingSettingsUiState.fromPrefs(prefs)
    }

    fun applyUiState(next: FloatingSettingsUiState) {
        if (uiState != next) uiState = next
    }

    LaunchedEffect(prefs) {
        applySnapshot(loadSnapshot())
    }

    LaunchedEffect(pastePackages, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        if (pastePackages == persistedPackages.paste) return@LaunchedEffect
        delay(300)
        withContext(Dispatchers.IO) {
            prefs.floatingWritePastePackages = pastePackages
        }
        persistedPackages.paste = pastePackages
    }

    fun refreshState() {
        scope.launch {
            applyUiState(loadUiState())
        }
    }

    fun requestOverlayPermission() {
        try {
            // 悬浮窗申请动作路由到宿主（AutoJs6 侧边栏权限区）
            PermissionRouter.route(context, BibiPermissionType.OVERLAY)
        } catch (e: Throwable) {
            Log.e(FLOATING_TAG, "Failed to request overlay permission", e)
        }
    }

    fun requestAccessibilityPermission() {
        try {
            // 无障碍开启动作路由到宿主
            PermissionRouter.route(context, BibiPermissionType.ACCESSIBILITY)
        } catch (e: Throwable) {
            Log.e(FLOATING_TAG, "Failed to request accessibility permission", e)
        }
    }

    fun showFloatingMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_floating_settings),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showOverlayPermissionMessage() {
        showFloatingMessage(R.string.toast_need_overlay_perm)
    }

    fun showAccessibilityPermissionMessage() {
        showFloatingMessage(R.string.toast_need_accessibility_perm)
    }

    fun floatingAsrNeedsAccessibilityWhenEnabled(): Boolean = true

    fun floatingAsrNeedsAccessibility(): Boolean = policyFloatingAsrNeedsAccessibility(
        floatingEnabled = uiState.asrEnabled
    )

    fun floatingInputNeedsAccessibility(): Boolean = policyFloatingInputNeedsAccessibility(
        floatingEnabled = uiState.asrEnabled,
        volumeKeyEnabled = uiState.volumeKeyRecordingEnabled,
        shakeRecordingEnabled = uiState.shakeRecordingEnabled
    )

    LaunchedEffect(
        settingsLoaded,
        uiState.asrEnabled,
        uiState.volumeKeyRecordingEnabled,
        uiState.shakeRecordingEnabled
    ) {
        if (!settingsLoaded || autoAccessibilityRequested) return@LaunchedEffect
        if (floatingInputNeedsAccessibility() && !isAccessibilityServiceEnabled(context)) {
            autoAccessibilityRequested = true
            requestAccessibilityPermission()
        }
    }

    fun setAsrEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            if (!Settings.canDrawOverlays(context)) {
                pendingAsrEnable = true
                pendingAsrPermission = FloatingPermissionRequest.Overlay
                showOverlayPermissionMessage()
                requestOverlayPermission()
                return false
            }
            if (floatingAsrNeedsAccessibilityWhenEnabled() && !isAccessibilityServiceEnabled(context)) {
                pendingAsrEnable = true
                pendingAsrPermission = FloatingPermissionRequest.Accessibility
                showAccessibilityPermissionMessage()
                requestAccessibilityPermission()
                return false
            }
        }

        pendingAsrEnable = false
        pendingAsrPermission = null
        prefs.floatingAsrEnabled = enabled
        if (enabled) {
            serviceManager.showAsrService()
        } else {
            serviceManager.hideAsrService()
        }
        refreshState()
        return true
    }

    fun setVolumeKeyRecordingEnabled(enabled: Boolean) {
        if (enabled && !isAccessibilityServiceEnabled(context)) {
            pendingVolumeKeyEnable = true
            showAccessibilityPermissionMessage()
            requestAccessibilityPermission()
            refreshState()
            return
        }
        pendingVolumeKeyEnable = false
        prefs.volumeKeyRecordingEnabled = enabled
        refreshState()
    }

    fun setShakeRecordingEnabled(enabled: Boolean) {
        if (enabled && !isAccessibilityServiceEnabled(context)) {
            pendingShakeEnable = true
            showAccessibilityPermissionMessage()
            requestAccessibilityPermission()
            refreshState()
            return
        }
        pendingShakeEnable = false
        prefs.shakeRecordingEnabled = enabled
        AsrAccessibilityService.refreshShakeSensor()
        refreshState()
    }

    fun syncAsrToggleAfterPermissions() {
        if (pendingVolumeKeyEnable && isAccessibilityServiceEnabled(context)) {
            setVolumeKeyRecordingEnabled(true)
            return
        }

        if (pendingShakeEnable && isAccessibilityServiceEnabled(context)) {
            setShakeRecordingEnabled(true)
            return
        }

        if (pendingAsrEnable) {
            val hasOverlay = Settings.canDrawOverlays(context)
            val hasAccessibility = isAccessibilityServiceEnabled(context)
            val needsAccessibility = floatingAsrNeedsAccessibilityWhenEnabled()
            if (hasOverlay && (!needsAccessibility || hasAccessibility)) {
                setAsrEnabled(true)
                return
            }
            if (
                hasOverlay &&
                needsAccessibility &&
                !hasAccessibility &&
                pendingAsrPermission == FloatingPermissionRequest.Overlay
            ) {
                pendingAsrPermission = FloatingPermissionRequest.Accessibility
                showAccessibilityPermissionMessage()
                requestAccessibilityPermission()
            }
            return
        }

        scope.launch {
            applyUiState(loadUiState())
        }
    }

    DisposableEffect(lifecycleOwner) {
        fun pendingPackageEdits(): FloatingPackageEdits? {
            if (!latestSettingsLoaded) return null
            val paste = latestPastePackages
            val pasteChanged = paste != persistedPackages.paste
            if (!pasteChanged) return null
            return FloatingPackageEdits(
                paste = paste,
                pasteChanged = pasteChanged
            )
        }

        fun flushPackageEditsAsync() {
            val edits = pendingPackageEdits() ?: return
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (edits.pasteChanged) prefs.floatingWritePastePackages = edits.paste
                }
                if (edits.pasteChanged) persistedPackages.paste = edits.paste
            }
        }

        fun flushPackageEditsNow() {
            val edits = pendingPackageEdits() ?: return
            if (edits.pasteChanged) prefs.floatingWritePastePackages = edits.paste
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    syncAsrToggleAfterPermissions()
                    // 入口纠偏：开关开着但唤醒服务未运行/不健康时自动拉起（幂等、退避节流）
                    try {
                        com.brycewg.asrkb.wake.WakeWatchdog.ensure(appContext)
                    } catch (_: Throwable) {
                    }
                }
                Lifecycle.Event.ON_PAUSE -> flushPackageEditsAsync()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushPackageEditsNow()
        }
    }

    fun applyExplainedSwitch(
        current: Boolean,
        target: Boolean,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        onConfirm: (Boolean) -> Unit
    ) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            currentState = current,
            preferenceKey = preferenceKey,
            onConfirm = {
                onConfirm(target)
                refreshState()
            }
        )
    }

    fun resultDisplayModeLabel(mode: ResultDisplayMode): String = context.getString(
        when (mode) {
            ResultDisplayMode.PANEL -> R.string.label_floating_style_panel
            ResultDisplayMode.CAPSULE -> R.string.label_floating_style_ball_only
        }
    )

    /** 「悬浮球样式」选择：panel=底部面板（原版），capsule=仅悬浮球。即时保存，下次进 LISTENING 生效。 */
    fun showResultDisplayModeSheet() {
        val modes = ResultDisplayMode.entries
        val selectedIndex = modes.indexOf(prefs.floatingResultDisplayMode).takeIf { it >= 0 } ?: 0
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_floating_ball_style),
            items = modes.map { resultDisplayModeLabel(it) },
            selectedIndex = selectedIndex
        ) { index ->
            prefs.floatingResultDisplayMode = modes.getOrElse(index) { ResultDisplayMode.PANEL }
            refreshState()
        }
    }

    fun volumeKeyModeLabel(mode: String): String = context.getString(
        when (mode) {
            Prefs.VOLUME_KEY_MODE_DOWN_TOGGLE -> R.string.option_volume_key_down_toggle
            Prefs.VOLUME_KEY_MODE_UP_START_DOWN_STOP -> R.string.option_volume_key_up_start_down_stop
            Prefs.VOLUME_KEY_MODE_DOWN_START_UP_STOP -> R.string.option_volume_key_down_start_up_stop
            else -> R.string.option_volume_key_up_toggle
        }
    )

    fun showVolumeKeyModeSheet() {
        val modes = listOf(
            Prefs.VOLUME_KEY_MODE_UP_TOGGLE,
            Prefs.VOLUME_KEY_MODE_DOWN_TOGGLE,
            Prefs.VOLUME_KEY_MODE_UP_START_DOWN_STOP,
            Prefs.VOLUME_KEY_MODE_DOWN_START_UP_STOP
        )
        val selectedIndex = modes.indexOf(uiState.volumeKeyRecordingMode).takeIf { it >= 0 } ?: 0
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_volume_key_recording_mode),
            items = modes.map { volumeKeyModeLabel(it) },
            selectedIndex = selectedIndex
        ) { index ->
            prefs.volumeKeyRecordingMode = modes.getOrElse(index) { Prefs.VOLUME_KEY_MODE_UP_TOGGLE }
            refreshState()
        }
    }

    fun shakeSensitivityLabel(id: String): String = context.getString(
        when (Prefs.ShakeRecordingSensitivity.fromId(id)) {
            Prefs.ShakeRecordingSensitivity.VERY_SENSITIVE ->
                R.string.option_shake_sensitivity_very_sensitive
            Prefs.ShakeRecordingSensitivity.SENSITIVE -> R.string.option_shake_sensitivity_sensitive
            Prefs.ShakeRecordingSensitivity.DEFAULT -> R.string.option_shake_sensitivity_default
            Prefs.ShakeRecordingSensitivity.CONSERVATIVE ->
                R.string.option_shake_sensitivity_conservative
            Prefs.ShakeRecordingSensitivity.VERY_CONSERVATIVE ->
                R.string.option_shake_sensitivity_very_conservative
        }
    )

    fun showShakeSensitivitySheet() {
        val sensitivities = Prefs.ShakeRecordingSensitivity.entries
        val selectedIndex = sensitivities.indexOfFirst {
            it.id == uiState.shakeRecordingSensitivity
        }.takeIf { it >= 0 } ?: sensitivities.indexOf(Prefs.ShakeRecordingSensitivity.DEFAULT)
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_shake_recording_sensitivity),
            items = sensitivities.map { shakeSensitivityLabel(it.id) },
            selectedIndex = selectedIndex
        ) { index ->
            prefs.shakeRecordingSensitivity = sensitivities.getOrElse(index) {
                Prefs.ShakeRecordingSensitivity.DEFAULT
            }.id
            refreshState()
        }
    }

    FloatingScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        SettingsChoiceSheet(
            state = choiceSheet,
            uiMode = uiMode,
            onDismiss = { choiceSheet = null }
        )
        SettingsFeatureExplainerDialog(
            state = featureExplainerDialog,
            uiMode = uiMode,
            onDismiss = { featureExplainerDialog = null }
        )
        SettingsMessageDialog(
            state = messageDialog,
            uiMode = uiMode,
            onDismiss = { messageDialog = null }
        )
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("preview") {
                FloatingPreviewCard(
                    uiMode = uiMode,
                    enabled = uiState.asrEnabled,
                    alphaPercent = uiState.alphaPercent,
                    sizeDp = uiState.sizeDp
                )
            }

            item("basic") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_floating_basic) {
                    val basicItemCount = if (uiState.asrEnabled) 6 else 1
                    FloatingExplainedSwitch(
                        id = "floating_asr",
                        titleRes = R.string.label_floating_asr,
                        checked = uiState.asrEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.asrEnabled,
                                target = target,
                                titleRes = R.string.label_floating_asr,
                                offDescRes = R.string.feature_floating_asr_off_desc,
                                onDescRes = R.string.feature_floating_asr_on_desc,
                                preferenceKey = "floating_asr_explained"
                            ) { setAsrEnabled(it) }
                        },
                        index = 0,
                        count = basicItemCount
                    )
                    if (uiState.asrEnabled) {
                        FloatingExplainedSwitch(
                            id = "floating_hold_to_record",
                            titleRes = R.string.label_floating_hold_to_record,
                            checked = uiState.holdToRecordEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.holdToRecordEnabled,
                                    target = target,
                                    titleRes = R.string.label_floating_hold_to_record,
                                    offDescRes = R.string.feature_floating_hold_to_record_off_desc,
                                    onDescRes = R.string.feature_floating_hold_to_record_on_desc,
                                    preferenceKey = "floating_hold_to_record_explained"
                                ) { prefs.floatingBallHoldToRecordEnabled = it }
                            },
                            index = 1,
                            count = basicItemCount
                        )
                        FloatingExplainedSwitch(
                            id = "floating_direct_drag",
                            titleRes = R.string.label_floating_direct_drag,
                            checked = uiState.directDragEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.directDragEnabled,
                                    target = target,
                                    titleRes = R.string.label_floating_direct_drag,
                                    offDescRes = R.string.feature_floating_direct_drag_off_desc,
                                    onDescRes = R.string.feature_floating_direct_drag_on_desc,
                                    preferenceKey = "floating_direct_drag_explained"
                                ) { prefs.floatingBallDirectDragEnabled = it }
                            },
                            index = 2,
                            count = basicItemCount
                        )
                        FloatingValuePreference(
                            titleRes = R.string.label_floating_ball_style,
                            value = resultDisplayModeLabel(prefs.floatingResultDisplayMode),
                            uiMode = uiMode,
                            index = 3,
                            count = basicItemCount,
                            onClick = { showResultDisplayModeSheet() }
                        )
                        FloatingSliderPreference(
                            titleRes = R.string.label_floating_alpha,
                            valueLabel = { "${it.roundFloatingToStep(5).toInt()}%" },
                            value = uiState.alphaPercent,
                            valueRange = 30f..100f,
                            step = 5,
                            uiMode = uiMode,
                            index = 4,
                            count = basicItemCount,
                            onValueChange = { value ->
                                uiState = uiState.copy(alphaPercent = value.roundFloatingToStep(5))
                            },
                            onValueChangeFinished = { value ->
                                val rounded = value.roundFloatingToStep(5)
                                prefs.floatingSwitcherAlpha = (rounded / 100f).coerceIn(0.2f, 1.0f)
                                serviceManager.refreshAsrService(uiState.asrEnabled)
                                refreshState()
                            }
                        )
                        FloatingSliderPreference(
                            titleRes = R.string.label_floating_size,
                            valueLabel = { "${it.roundFloatingToStep(4).toInt().coerceIn(28, 96)} dp" },
                            value = uiState.sizeDp.toFloat(),
                            valueRange = 28f..96f,
                            step = 4,
                            uiMode = uiMode,
                            index = 5,
                            count = basicItemCount,
                            onValueChange = { value ->
                                uiState = uiState.copy(sizeDp = value.roundFloatingToStep(4).toInt().coerceIn(28, 96))
                            },
                            onValueChangeFinished = { value ->
                                val next = value.roundFloatingToStep(4).toInt().coerceIn(28, 96)
                                prefs.floatingBallSizeDp = next
                                if (uiState.asrEnabled) {
                                    serviceManager.showAsrService()
                                }
                                refreshState()
                            }
                        )
                        FloatingResetButton(
                            uiMode = uiMode,
                            onClick = {
                                val messageRes = if (resetFloatingPosition(context, prefs, serviceManager)) {
                                    R.string.toast_floating_position_reset
                                } else {
                                    R.string.toast_debug_failed
                                }
                                showFloatingMessage(messageRes)
                            }
                        )
                    }
                }
            }

            item("volume_key") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_volume_key_recording) {
                    val volumeItemCount = if (uiState.volumeKeyRecordingEnabled) 3 else 1
                    FloatingExplainedSwitch(
                        id = "volume_key_recording",
                        titleRes = R.string.label_volume_key_recording,
                        checked = uiState.volumeKeyRecordingEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.volumeKeyRecordingEnabled,
                                target = target,
                                titleRes = R.string.label_volume_key_recording,
                                offDescRes = R.string.feature_volume_key_recording_off_desc,
                                onDescRes = R.string.feature_volume_key_recording_on_desc,
                                preferenceKey = "volume_key_recording_explained"
                            ) { setVolumeKeyRecordingEnabled(it) }
                        },
                        index = 0,
                        count = volumeItemCount
                    )
                    if (uiState.volumeKeyRecordingEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_volume_key_recording_mode,
                            value = volumeKeyModeLabel(uiState.volumeKeyRecordingMode),
                            uiMode = uiMode,
                            index = 1,
                            count = volumeItemCount,
                            onClick = {
                                showVolumeKeyModeSheet()
                            }
                        )
                        FloatingExplainedSwitch(
                            id = "volume_key_status_toast",
                            titleRes = R.string.label_volume_key_status_toast,
                            checked = uiState.volumeKeyStatusToastEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.volumeKeyStatusToastEnabled,
                                    target = target,
                                    titleRes = R.string.label_volume_key_status_toast,
                                    offDescRes = R.string.feature_volume_key_status_toast_off_desc,
                                    onDescRes = R.string.feature_volume_key_status_toast_on_desc,
                                    preferenceKey = "volume_key_status_toast_explained"
                                ) { prefs.volumeKeyStatusToastEnabled = it }
                            },
                            index = 2,
                            count = volumeItemCount
                        )
                    }
                }
            }

            item("shake_recording") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_shake_recording) {
                    val shakeItemCount = if (uiState.shakeRecordingEnabled) 3 else 1
                    FloatingExplainedSwitch(
                        id = "shake_recording",
                        titleRes = R.string.label_shake_recording,
                        checked = uiState.shakeRecordingEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.shakeRecordingEnabled,
                                target = target,
                                titleRes = R.string.label_shake_recording,
                                offDescRes = R.string.feature_shake_recording_off_desc,
                                onDescRes = R.string.feature_shake_recording_on_desc,
                                preferenceKey = "shake_recording_explained"
                            ) { setShakeRecordingEnabled(it) }
                        },
                        index = 0,
                        count = shakeItemCount
                    )
                    if (uiState.shakeRecordingEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_shake_recording_sensitivity,
                            value = shakeSensitivityLabel(uiState.shakeRecordingSensitivity),
                            uiMode = uiMode,
                            index = 1,
                            count = shakeItemCount,
                            onClick = { showShakeSensitivitySheet() }
                        )
                        FloatingExplainedSwitch(
                            id = "shake_recording_sound",
                            titleRes = R.string.label_shake_recording_sound,
                            checked = uiState.shakeRecordingSoundEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.shakeRecordingSoundEnabled,
                                    target = target,
                                    titleRes = R.string.label_shake_recording_sound,
                                    offDescRes = R.string.feature_shake_recording_sound_off_desc,
                                    onDescRes = R.string.feature_shake_recording_sound_on_desc,
                                    preferenceKey = "shake_recording_sound_explained"
                                ) { prefs.shakeRecordingSoundEnabled = it }
                            },
                            index = 2,
                            count = shakeItemCount
                        )
                    }
                }
            }

            item("wake_word") {
                // 开关显示服务实况而非偏好值：Idle 灭，其余（启动中/监听/自愈/让位）亮
                val wakeStatus = wakeServiceState.status
                val wakeSwitchOn = wakeStatus != WakeServiceState.Status.Idle
                val wakeSummary = when (wakeStatus) {
                    WakeServiceState.Status.Retrying -> stringResource(
                        when (wakeServiceState.failReason) {
                            WakeServiceState.FailReason.Permission -> R.string.wake_status_waiting_permission
                            WakeServiceState.FailReason.Engine -> R.string.wake_status_engine_retry
                            WakeServiceState.FailReason.Audio -> R.string.wake_status_waiting_audio
                            WakeServiceState.FailReason.Unknown, null -> R.string.wake_status_recovering
                        }
                    )
                    WakeServiceState.Status.Idle ->
                        if (uiState.wakeWordEnabled) stringResource(R.string.wake_status_not_running) else null
                    else -> null
                }
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_wake_word) {
                    FloatingExplainedSwitch(
                        id = "wake_word_enabled",
                        titleRes = R.string.label_wake_word_enabled,
                        checked = wakeSwitchOn,
                        summary = wakeSummary,
                        onToggle = { target ->
                            if (target &&
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                // 缺麦克风权限：路由到宿主权限区，不写开关
                                try {
                                    PermissionRouter.route(context, BibiPermissionType.MICROPHONE)
                                } catch (t: Throwable) {
                                    Log.e(FLOATING_TAG, "Failed to route microphone permission", t)
                                }
                            } else {
                                prefs.wakeWordEnabled = target
                                if (target) {
                                    com.brycewg.asrkb.wake.WakeWordService.start(context)
                                } else {
                                    com.brycewg.asrkb.wake.WakeWordService.stop(context)
                                }
                                refreshState()
                            }
                        },
                        index = 0,
                        count = 2
                    )
                    if (wakeSwitchOn) {
                        FloatingValuePreference(
                            titleRes = R.string.label_wake_word_selected,
                            value = if (prefs.wakeWordSelected.isBlank()) {
                                stringResource(R.string.wake_word_all)
                            } else {
                                prefs.wakeWordSelected
                            },
                            uiMode = uiMode,
                            index = 1,
                            count = 2,
                            onClick = onOpenWakeManager
                        )
                    }
                }
            }

            item("compat") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_floating_compat) {
                    FloatingExplainedSwitch(
                        id = "floating_write_paste",
                        titleRes = R.string.label_floating_write_paste,
                        checked = uiState.writePasteEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.writePasteEnabled,
                                target = target,
                                titleRes = R.string.label_floating_write_paste,
                                offDescRes = R.string.feature_floating_write_paste_off_desc,
                                onDescRes = R.string.feature_floating_write_paste_on_desc,
                                preferenceKey = "floating_write_paste_explained"
                            ) { prefs.floatingWriteTextPasteEnabled = it }
                        },
                        index = 0,
                        count = 2
                    )
                    FloatingPackagesField(
                        value = pastePackages,
                        onValueChange = {
                            pastePackages = it
                        },
                        label = stringResource(R.string.label_floating_write_paste_pkgs),
                        helper = stringResource(R.string.hint_floating_write_paste_pkgs),
                        uiMode = uiMode,
                        index = 1,
                        count = 2
                    )
                }
            }
        }
    }
}
