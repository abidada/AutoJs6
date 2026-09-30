/**
 * Lightweight recording-state holder for external queries (e.g. the `$bibi.isRecording()` JS API).
 *
 * 归属模块：悬浮球识别会话
 *
 * Maintained by `AsrSessionManager` at session start/stop/cancel/final/error transitions.
 * Approximates "the recording engine is capturing" — independent of floating ball visuals.
 *
 * zh-CN: 供 `$bibi.isRecording()` 查询的轻量录音状态, 由 AsrSessionManager 在
 * 会话开始/结束/取消/完成/出错时维护.
 */
package com.brycewg.asrkb.host

object AsrRecordingState {

    @Volatile
    @JvmStatic
    var active: Boolean = false
        private set

    @JvmStatic
    fun set(active: Boolean) {
        this.active = active
    }
}
