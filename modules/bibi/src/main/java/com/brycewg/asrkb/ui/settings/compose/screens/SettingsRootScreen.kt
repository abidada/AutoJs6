/**
 * 设置页 Compose 路由宿主。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHighlightTarget
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsMotion
import com.brycewg.asrkb.ui.settings.compose.state.SettingsHostUiState

@OptIn(ExperimentalActivityApi::class)
@Composable
fun SettingsRootScreen(
    uiState: SettingsHostUiState,
    hasUpdateAvailable: Boolean,
    onSelectTab: (Int) -> Unit,
    onPushRoute: (BibiSettingsRoute) -> Unit,
    onOpenRoute: (BibiSettingsRoute, String?) -> Unit,
    onPopRoute: () -> Boolean,
    onConsumeRouteArg: () -> Unit,
    actions: SettingsActionController
) {
    val canHandleBack = uiState.backStack.size > 1 || uiState.selectedHomeTab != 0
    fun handleBack() {
        if (!onPopRoute() && uiState.selectedHomeTab != 0) {
            onSelectTab(0)
        }
    }
    PredictiveBackHandler(enabled = canHandleBack) { progress ->
        var completed = false
        try {
            progress.collect { }
            completed = true
        } finally {
            if (completed) {
                handleBack()
            }
        }
    }

    val currentRoute = uiState.backStack.last()
    val transitionState = remember(currentRoute, uiState.backStack.size) {
        SettingsRouteTransitionState(
            route = currentRoute,
            depth = uiState.backStack.lastIndex
        )
    }

    AnimatedContent(
        targetState = transitionState,
        transitionSpec = { settingsRouteTransitionSpec() },
        contentKey = { it.route },
        label = "SettingsRouteContent"
    ) { target ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(blockRouteInputDuringTransition())
        ) {
            CompositionLocalProvider(LocalSettingsHighlightTarget provides uiState.highlightTargetId) {
                SettingsRouteContent(
                    route = target.route,
                    uiState = uiState,
                    hasUpdateAvailable = hasUpdateAvailable,
                    onSelectTab = onSelectTab,
                    onPushRoute = onPushRoute,
                    onOpenRoute = onOpenRoute,
                    onPopRoute = onPopRoute,
                    onConsumeRouteArg = onConsumeRouteArg,
                    actions = actions
                )
            }
        }
    }
}

private fun AnimatedContentScope.blockRouteInputDuringTransition(): Modifier {
    if (!transition.isRunning) return Modifier
    return Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

@Composable
private fun SettingsRouteContent(
    route: BibiSettingsRoute,
    uiState: SettingsHostUiState,
    hasUpdateAvailable: Boolean,
    onSelectTab: (Int) -> Unit,
    onPushRoute: (BibiSettingsRoute) -> Unit,
    onOpenRoute: (BibiSettingsRoute, String?) -> Unit,
    onPopRoute: () -> Boolean,
    onConsumeRouteArg: () -> Unit,
    actions: SettingsActionController
) {
    when (route) {
        BibiSettingsRoute.Home -> SettingsHomeScreen(
            selectedTab = uiState.selectedHomeTab,
            hasUpdateAvailable = hasUpdateAvailable,
            onSelectTab = onSelectTab,
            onPushRoute = onPushRoute,
            actions = actions
        )

        BibiSettingsRoute.About -> AboutSettingsScreen(
            onBack = { onPopRoute() },
            actions = actions
        )

        BibiSettingsRoute.UsageStats -> UsageStatsSettingsScreen(
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.Backup -> BackupSettingsScreen(
            onBack = { onPopRoute() },
            actions = actions
        )

        BibiSettingsRoute.Input -> InputSettingsScreen(
            onBack = { onPopRoute() },
            actions = actions
        )

        BibiSettingsRoute.UiSettings -> UiSettingsScreen(
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.RecordingTest -> RecordingTestScreen(
            onBack = { onPopRoute() },
            onOpenAsrSettings = { onPushRoute(BibiSettingsRoute.Asr) }
        )

        BibiSettingsRoute.Floating -> FloatingSettingsRoute(
            onBack = { onPopRoute() },
            actions = actions
        )

        BibiSettingsRoute.Other -> OtherSettingsScreen(
            onBack = { onPopRoute() },
            actions = actions
        )

        BibiSettingsRoute.Ai -> AiSettingsScreen(
            onBack = { onPopRoute() },
            onOpenPromptSelection = { onPushRoute(BibiSettingsRoute.PromptSelection) },
            actions = actions
        )

        BibiSettingsRoute.PromptSelection -> PromptSelectionSettingsScreen(
            onBack = { onPopRoute() },
            onOpenPreview = { onPushRoute(BibiSettingsRoute.PromptSelectionPreview) }
        )

        BibiSettingsRoute.PromptSelectionPreview -> PromptSelectionPreviewScreen(
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.Asr -> AsrSettingsScreen(
            onBack = { onPopRoute() },
            onOpenVoiceTest = { onPushRoute(BibiSettingsRoute.RecordingTest) },
            actions = actions
        )

        BibiSettingsRoute.Tts -> TtsSettingsScreen(
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.VoiceDispatch -> VoiceDispatchRoute(
            presetScriptPath = uiState.routeArg,
            onConsumePreset = onConsumeRouteArg,
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.FloatingPanel -> FloatingPanelSettingsScreen(
            onBack = { onPopRoute() }
        )

        BibiSettingsRoute.Search -> SettingsSearchScreen(
            onBack = { onPopRoute() },
            onOpenEntry = { entry ->
                actions.applySearchEntry(entry)
                entry.composeRoute?.let { route -> onOpenRoute(route, entry.targetEntryId) }
            }
        )

        BibiSettingsRoute.History -> SettingsHistoryRoute(
            onBack = { onPopRoute() },
            onOpenApiLog = { onPushRoute(BibiSettingsRoute.ApiLog) }
        )

        BibiSettingsRoute.ApiLog -> SettingsApiLogRoute(
            onBack = { onPopRoute() }
        )
    }
}

private data class SettingsRouteTransitionState(
    val route: BibiSettingsRoute,
    val depth: Int
)

private fun AnimatedContentTransitionScope<SettingsRouteTransitionState>.settingsRouteTransitionSpec(): ContentTransform {
    if (targetState.depth == initialState.depth) {
        return (
            fadeIn(animationSpec = tween(SettingsMotion.ROUTE_REPLACE_FADE_IN_MILLIS)) togetherWith
                fadeOut(animationSpec = tween(SettingsMotion.ROUTE_REPLACE_FADE_OUT_MILLIS))
            ).using(SizeTransform(clip = false) { _, _ -> snap() })
    }

    val forward = targetState.depth > initialState.depth
    val enterTransition = slideInHorizontally(
        animationSpec = SettingsMotion.routeEnterSpatialSpec()
    ) { fullWidth ->
        if (forward) {
            fullWidth
        } else {
            -fullWidth / SettingsMotion.ROUTE_BACKGROUND_OFFSET_DIVISOR
        }
    }
    val exitTransition = slideOutHorizontally(
        animationSpec = SettingsMotion.routeExitSpatialSpec()
    ) { fullWidth ->
        if (forward) {
            -fullWidth / SettingsMotion.ROUTE_BACKGROUND_OFFSET_DIVISOR
        } else {
            fullWidth
        }
    }
    return ContentTransform(
        targetContentEnter = enterTransition,
        initialContentExit = exitTransition,
        targetContentZIndex = if (forward) {
            SettingsMotion.ROUTE_FOREGROUND_Z_INDEX
        } else {
            SettingsMotion.ROUTE_BACKGROUND_Z_INDEX
        },
        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() }
    )
}
