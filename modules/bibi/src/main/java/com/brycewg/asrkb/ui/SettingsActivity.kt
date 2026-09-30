/**
 * 主设置入口页面。
 *
 * 提供一键设置流程、入口导航与常用工具能力。
 */
package com.brycewg.asrkb.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.host.BibiPermissionType
import com.brycewg.asrkb.host.PermissionRouter
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTestInputSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsUpdateHost
import com.brycewg.asrkb.ui.settings.compose.components.SettingsUpdateUiState
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsTheme
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.screens.SettingsRootScreen
import com.brycewg.asrkb.ui.settings.compose.state.SettingsEntryEffectsCoordinator
import com.brycewg.asrkb.ui.settings.compose.state.SettingsHostViewModel
import com.brycewg.asrkb.ui.settings.compose.state.SettingsUpdateCoordinator
import com.brycewg.asrkb.ui.setup.SetupState
import com.brycewg.asrkb.ui.setup.SetupStateMachine
import com.brycewg.asrkb.util.HapticFeedbackHelper

/**
 * 主设置页面
 *
 * 提供：
 * - 一键设置流程（基于状态机）
 * - 更新检查（通过 SettingsUpdateCoordinator）
 * - 设置导入/导出
 * - 子设置页导航
 * - 测试输入体验
 */
class SettingsActivity : BaseActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_INITIAL_ROUTE = "extra_initial_settings_route"
    }

    // 一键设置状态机
    private lateinit var setupStateMachine: SetupStateMachine

    private lateinit var prefs: Prefs
    private lateinit var entryEffectsCoordinator: SettingsEntryEffectsCoordinator
    private lateinit var updateCoordinator: SettingsUpdateCoordinator

    // Handler 用于延迟任务
    private val handler = Handler(Looper.getMainLooper())

    private val testInputSheetVisible = mutableStateOf(false)
    private val systemActionDialogState = mutableStateOf<SettingsMessageDialogState?>(null)
    private val pendingInitialRoute = mutableStateOf<BibiSettingsRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        // 初始化状态机和工具类
        setupStateMachine = SetupStateMachine(this, ::showSetupStateMessage)
        entryEffectsCoordinator = SettingsEntryEffectsCoordinator(
            activity = this,
            showSystemMessage = ::showSystemActionDialog
        )
        updateCoordinator = SettingsUpdateCoordinator(this)

        val actionController = SettingsActionController(this)
        val coldStartRoute = if (savedInstanceState == null) {
            consumeInitialRouteExtra(intent)
        } else {
            null
        }
        setContent {
            val viewModel: SettingsHostViewModel = viewModel(
                factory = remember {
                    settingsHostViewModelFactory(application, coldStartRoute)
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val routeToOpen = pendingInitialRoute.value

            LaunchedEffect(routeToOpen) {
                routeToOpen?.let { route ->
                    viewModel.openExternalRoute(route)
                    pendingInitialRoute.value = null
                }
            }

            BibiSettingsTheme(
                uiMode = uiState.uiMode,
                themeMode = uiState.themeMode
            ) {
                val hasUpdateAvailable by remember {
                    derivedStateOf { updateCoordinator.uiState.value is SettingsUpdateUiState.UpdateAvailable }
                }
                CompositionLocalProvider(LocalSettingsHapticTap provides actionController::hapticTap) {
                    SettingsRootScreen(
                        uiState = uiState,
                        hasUpdateAvailable = hasUpdateAvailable,
                        onSelectTab = viewModel::selectHomeTab,
                        onPushRoute = viewModel::push,
                        onOpenRoute = viewModel::openRoute,
                        onPopRoute = viewModel::pop,
                        onSetUiMode = viewModel::setUiMode,
                        onSetThemeMode = viewModel::setThemeMode,
                        actions = actionController
                    )
                    settingsOverlayHosts(uiMode = uiState.uiMode)
                }
            }
        }

    }

    @Composable
    private fun settingsOverlayHosts(uiMode: BibiUiMode) {
        SettingsTestInputSheet(
            show = testInputSheetVisible.value,
            uiMode = uiMode,
            onDismiss = { testInputSheetVisible.value = false }
        )
        SettingsUpdateHost(
            state = updateCoordinator.uiState.value,
            uiMode = uiMode,
            onDismiss = updateCoordinator::dismiss,
            onDownload = updateCoordinator::showDownloadSources,
            onOpenReleasePage = updateCoordinator::openReleasePage,
            onOpenChangelog = updateCoordinator::openChangelogHistory,
            onManualCheck = updateCoordinator::openManualReleasePage,
            onSelectDownloadSource = updateCoordinator::startDownload
        )
        SettingsMessageDialog(
            state = systemActionDialogState.value,
            uiMode = uiMode,
            onDismiss = { systemActionDialogState.value = null }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInitialRoute.value = consumeInitialRouteExtra(intent)
    }

    private fun settingsHostViewModelFactory(
        application: Application,
        initialRoute: BibiSettingsRoute?
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsHostViewModel(application, initialRoute) as T
    }

    private fun consumeInitialRouteExtra(intent: Intent?): BibiSettingsRoute? {
        val route = BibiSettingsRoute.fromId(intent?.getStringExtra(EXTRA_INITIAL_ROUTE))
            ?: return null
        intent?.removeExtra(EXTRA_INITIAL_ROUTE)
        return route
    }

    override fun onResume() {
        super.onResume()

        updateCoordinator.onResume()
        entryEffectsCoordinator.onResume()

        // 若处于一键设置流程中，返回后继续推进
        advanceSetupIfInProgress()

        // 匿名数据采集选择已整合进新手引导页，此处不再自动弹窗
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 权限请求结果返回后，继续推进一键设置流程
        if (setupStateMachine.currentState is SetupState.RequestingPermissions) {
            Log.d(TAG, "Permission result received, advancing setup")
            // 小延迟，等待系统状态稳定
            handler.postDelayed({ advanceSetupIfInProgress() }, 200)
        }
    }

    fun startOneClickSetupFromCompose() {
        startOneClickSetup()
    }

    fun checkForUpdatesFromCompose() {
        updateCoordinator.checkForUpdates()
    }

    fun showTestInputFromCompose() {
        testInputSheetVisible.value = true
    }

    fun hapticTapFromCompose() {
        hapticTapIfEnabled(null)
    }

    fun updatesEnabledFromCompose(): Boolean = updateCoordinator.updatesEnabled

    fun showSystemActionDialogFromCompose(
        titleRes: Int,
        messageRes: Int
    ) {
        showSystemActionDialog(titleRes = titleRes, messageRes = messageRes)
    }

    // ==================== 一键设置相关 ====================

    /**
     * 启动一键设置流程
     */
    private fun startOneClickSetup() {
        Log.d(TAG, "Starting one-click setup")

        // 重置状态机
        setupStateMachine.reset()

        // 推进到第一个状态
        advanceSetupStateMachine()
    }

    /**
     * 推进一键设置状态机
     *
     * 1. 调用状态机的 advance() 方法获取下一个状态
     * 2. 执行该状态对应的操作
     */
    private fun advanceSetupStateMachine() {
        val newState = setupStateMachine.advance()
        val didExecute = setupStateMachine.executeCurrentStateAction()

        Log.d(TAG, "Setup state: $newState, executed action: $didExecute")

        when (newState) {
            is SetupState.Completed, is SetupState.Aborted -> {
                // 设置完成或中止
            }

            is SetupState.RequestingPermissions -> {
                // 权限请求阶段，某些权限需要通过 Activity 的回调处理
                if (didExecute) {
                    val state = setupStateMachine.getCurrentPermissionState()
                    if (state?.askedMic == true && !hasRecordAudioPermission()) {
                        // 麦克风申请动作路由到宿主（AutoJs6 侧边栏权限区），返回后由 onResume 推进流程
                        PermissionRouter.route(this, BibiPermissionType.MICROPHONE)
                    } else if (state?.askedNotif == true && !state.askedA11y) {
                        // Android 13+ 通知权限请求
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                // 通知申请动作路由到宿主
                                PermissionRouter.route(this, BibiPermissionType.NOTIFICATIONS)
                            } else {
                                // 已授予，继续推进
                                handler.postDelayed({ advanceSetupStateMachine() }, 200)
                            }
                        } else {
                            // Android 12 及以下，跳过
                            handler.postDelayed({ advanceSetupStateMachine() }, 200)
                        }
                    }
                }
            }

            else -> {
                // 其他状态，无需特殊处理
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 如果正在一键设置流程中，继续推进
     */
    private fun advanceSetupIfInProgress() {
        if (setupStateMachine.currentState !is SetupState.NotStarted &&
            setupStateMachine.currentState !is SetupState.Completed &&
            setupStateMachine.currentState !is SetupState.Aborted
        ) {
            Log.d(TAG, "Resuming setup flow")
            handler.post { advanceSetupStateMachine() }
        }
    }

    private fun showSystemActionDialog(
        titleRes: Int,
        messageRes: Int
    ) {
        systemActionDialogState.value = SettingsMessageDialogState(
            title = getString(titleRes),
            message = getString(messageRes),
            confirmText = getString(android.R.string.ok)
        )
    }

    private fun showSetupStateMessage(message: String) {
        systemActionDialogState.value = SettingsMessageDialogState(
            title = getString(R.string.btn_one_click_setup),
            message = message,
            confirmText = getString(android.R.string.ok)
        )
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }
}
