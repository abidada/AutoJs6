/**
 * 设置页 Compose 宿主状态。
 *
 * 归属模块：ui/settings/compose/state
 */
package com.brycewg.asrkb.ui.settings.compose.state

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsHostUiState(
    val selectedHomeTab: Int = 0,
    val highlightTargetId: String? = null,

    /** 一次性路由参数（外部深链附带，如待建规则的脚本路径），由目标页消费后置空。 */
    val routeArg: String? = null,

    val backStack: List<BibiSettingsRoute> = listOf(BibiSettingsRoute.Home)
)

class SettingsHostViewModel(
    application: Application,
    initialRoute: BibiSettingsRoute? = null,
    initialRouteArg: String? = null
) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val _uiState = MutableStateFlow(
        SettingsHostUiState().let { state ->
            if (initialRoute == null) {
                state
            } else {
                state.openedFromOutside(initialRoute).copy(routeArg = initialRouteArg)
            }
        }
    )
    val uiState: StateFlow<SettingsHostUiState> = _uiState.asStateFlow()

    fun selectHomeTab(index: Int) {
        _uiState.update { it.copy(selectedHomeTab = index.coerceIn(0, 2)) }
    }

    fun push(route: BibiSettingsRoute) {
        _uiState.update { state ->
            if (state.backStack.lastOrNull() == route) {
                state
            } else {
                state.copy(backStack = state.backStack + route)
            }
        }
    }

    fun openRoute(route: BibiSettingsRoute) {
        openRoute(route, highlightTargetId = null)
    }

    fun openRoute(route: BibiSettingsRoute, highlightTargetId: String?) {
        if (route == BibiSettingsRoute.Home) {
            _uiState.update {
                it.copy(
                    highlightTargetId = highlightTargetId,
                    backStack = listOf(BibiSettingsRoute.Home)
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                selectedHomeTab = route.homeTabIndex(),
                highlightTargetId = highlightTargetId,
                backStack = listOf(BibiSettingsRoute.Home, route)
            )
        }
    }

    /**
     * 通知、系统入口等外部跳转。
     *
     * 丢掉当前所在页面，按目标页自己的上级重建返回栈。
     * 例如后台停在 AI 设置时点进识别历史，返回落到「智能」而不是 AI 设置。
     */
    fun openExternalRoute(route: BibiSettingsRoute, routeArg: String? = null) {
        _uiState.update { it.openedFromOutside(route).copy(routeArg = routeArg) }
    }

    /** 目标页消费一次性路由参数后调用。 */
    fun consumeRouteArg() {
        _uiState.update { state ->
            if (state.routeArg == null) state else state.copy(routeArg = null)
        }
    }

    private fun SettingsHostUiState.openedFromOutside(route: BibiSettingsRoute): SettingsHostUiState {
        if (route == BibiSettingsRoute.Home) {
            return copy(
                highlightTargetId = null,
                backStack = listOf(BibiSettingsRoute.Home)
            )
        }
        return copy(
            selectedHomeTab = route.homeTabIndex(),
            highlightTargetId = null,
            backStack = listOf(BibiSettingsRoute.Home, route)
        )
    }

    fun pop(): Boolean {
        val state = _uiState.value
        if (state.backStack.size <= 1) return false
        _uiState.value = state.copy(backStack = state.backStack.dropLast(1))
        return true
    }

    private fun BibiSettingsRoute.homeTabIndex(): Int = when (this) {
        BibiSettingsRoute.Input,
        BibiSettingsRoute.UiSettings,
        BibiSettingsRoute.RecordingTest,
        BibiSettingsRoute.Floating,
        BibiSettingsRoute.FloatingPanel -> 0

        BibiSettingsRoute.Asr,
        BibiSettingsRoute.Tts,
        BibiSettingsRoute.Ai,
        BibiSettingsRoute.PromptSelection,
        BibiSettingsRoute.PromptSelectionPreview,
        BibiSettingsRoute.History,
        BibiSettingsRoute.ApiLog -> 1

        BibiSettingsRoute.VoiceDispatch,
        BibiSettingsRoute.Backup,
        BibiSettingsRoute.Other,
        BibiSettingsRoute.About,
        BibiSettingsRoute.UsageStats,
        BibiSettingsRoute.Search,
        BibiSettingsRoute.Home -> 2
    }

    private fun refreshInputSurfaces() {
        val app = getApplication<Application>()
        if (prefs.floatingAsrEnabled) {
            app.startService(
                Intent(app, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_REFRESH_UI
                }
            )
        }
    }
}
