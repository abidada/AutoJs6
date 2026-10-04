/**
 * 悬浮球展开面板的菜单项目录。
 *
 * 面板显示的内容与顺序由设置页维护的数据源决定（[Prefs.floatingPanelItemsJson]），
 * 本枚举只定义全集目录（稳定 id + 静态图标 + 文案资源）；运行态图标（fill 变体）
 * 与点击动作由 FloatingAsrInteractionController 按 id 构建。
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.serialization.json.Json

enum class FloatingPanelItemId(
    val id: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
) {
    DispatchContinue("dispatch_continue", R.string.label_dispatch_continue, R.drawable.circles_four),
    SwitchPrompt("switch_prompt", R.string.label_radial_switch_prompt, R.drawable.article),
    SwitchAsr("switch_asr", R.string.label_radial_switch_asr, R.drawable.waveform),
    MoveBall("move_ball", R.string.label_radial_move, R.drawable.arrows_out_cardinal),
    SilenceAutoStop("silence_autostop", R.string.label_recording_auto_stop_mode, R.drawable.hand_palm),
    PostProc("postproc", R.string.label_radial_postproc, R.drawable.magic_wand),
    History("history", R.string.label_radial_open_history, R.drawable.textbox),
    ClipboardUpload("clipboard_upload", R.string.label_radial_clipboard_upload, R.drawable.cloud_arrow_up),
    ClipboardPull("clipboard_pull", R.string.label_radial_clipboard_pull, R.drawable.cloud_arrow_down),
    WakeWord("wake_word", R.string.label_wake_word_enabled, R.drawable.microphone),
    TtsAnnounce("tts_announce", R.string.label_tts_enabled, R.drawable.speaker_high),
    Settings("settings", R.string.label_radial_open_settings, R.drawable.gear);

    companion object {
        private const val TAG = "FloatingPanelItemId"

        /** 默认面板顺序 = 全集，与历史硬编码顺序一致。 */
        val defaultOrder: List<FloatingPanelItemId> = entries.toList()

        fun fromId(id: String): FloatingPanelItemId? = entries.firstOrNull { it.id == id }

        /** 从 Prefs 读取面板顺序；未配置/解析失败/含未知 id 时回退默认全集。 */
        fun loadOrder(prefs: Prefs): List<FloatingPanelItemId> {
            val raw = prefs.floatingPanelItemsJson
            if (raw.isBlank()) return defaultOrder
            return try {
                Json.decodeFromString<List<String>>(raw)
                    .mapNotNull(::fromId)
                    .distinct()
                    .ifEmpty { defaultOrder }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse floating panel items, fallback to default", e)
                defaultOrder
            }
        }

        /** 保存面板顺序到 Prefs（id 列表 JSON）。 */
        fun saveOrder(prefs: Prefs, items: List<FloatingPanelItemId>) {
            try {
                prefs.floatingPanelItemsJson = Json.encodeToString(items.map { it.id })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save floating panel items", e)
            }
        }
    }
}
