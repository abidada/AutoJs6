/**
 * 设置页 Compose 路由定义。
 *
 * 归属模块：ui/settings/compose/core
 */
package com.brycewg.asrkb.ui.settings.compose.core

sealed interface BibiSettingsRoute {
    val id: String

    data object Home : BibiSettingsRoute {
        override val id: String = "home"
    }

    data object Input : BibiSettingsRoute {
        override val id: String = "input"
    }

    data object UiSettings : BibiSettingsRoute {
        override val id: String = "ui_settings"
    }

    data object RecordingTest : BibiSettingsRoute {
        override val id: String = "recording_test"
    }

    data object Floating : BibiSettingsRoute {
        override val id: String = "floating"
    }

    data object Asr : BibiSettingsRoute {
        override val id: String = "asr"
    }

    /** TTS 语音配置（播报设置 / 服务商与本地模型 / 试听）。 */
    data object Tts : BibiSettingsRoute {
        override val id: String = "tts"
    }

    data object Ai : BibiSettingsRoute {
        override val id: String = "ai"
    }

    /** AI 设置下的“润色模式（自动选择提示词）”独立页面。 */
    data object PromptSelection : BibiSettingsRoute {
        override val id: String = "prompt_selection"
    }

    data object PromptSelectionPreview : BibiSettingsRoute {
        override val id: String = "prompt_selection_preview"
    }

    /** 语音分发规则管理页（宿主侧边栏「文字分发」入口直达）。 */
    data object VoiceDispatch : BibiSettingsRoute {
        override val id: String = "voice_dispatch"
    }

    /** 悬浮球面板设置页（面板菜单项内容与顺序的数据源管理端）。 */
    data object FloatingPanel : BibiSettingsRoute {
        override val id: String = "floating_panel"
    }

    data object Backup : BibiSettingsRoute {
        override val id: String = "backup"
    }

    data object Other : BibiSettingsRoute {
        override val id: String = "other"
    }

    data object About : BibiSettingsRoute {
        override val id: String = "about"
    }

    data object UsageStats : BibiSettingsRoute {
        override val id: String = "usage_stats"
    }

    data object Search : BibiSettingsRoute {
        override val id: String = "search"
    }

    data object History : BibiSettingsRoute {
        override val id: String = "history"
    }

    data object ApiLog : BibiSettingsRoute {
        override val id: String = "api_log"
    }

    companion object {
        fun fromId(id: String?): BibiSettingsRoute? = when (id) {
            Home.id -> Home
            Input.id -> Input
            UiSettings.id -> UiSettings
            RecordingTest.id -> RecordingTest
            Floating.id -> Floating
            Asr.id -> Asr
            Tts.id -> Tts
            Ai.id -> Ai
            PromptSelection.id -> PromptSelection
            PromptSelectionPreview.id -> PromptSelectionPreview
            VoiceDispatch.id -> VoiceDispatch
            FloatingPanel.id -> FloatingPanel
            Backup.id -> Backup
            Other.id -> Other
            About.id -> About
            UsageStats.id -> UsageStats
            Search.id -> Search
            History.id -> History
            ApiLog.id -> ApiLog
            else -> null
        }
    }
}
