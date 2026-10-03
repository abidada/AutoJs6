/**
 * 语音唤醒服务健康状态单例：由 WakeWordService 在生命周期关键节点维护，
 * 供设置页（开关如实显示）与看门狗（健康判定）读取。与 UI 同进程，直读无需绑定/广播。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object WakeServiceState {

    enum class Status {
        /** 服务未运行 */
        Idle,

        /** 启动中（引擎加载中） */
        Starting,

        /** 正常监听 */
        Listening,

        /** 自愈重试中（权限缺失/引擎失败/麦克风被占用） */
        Retrying,

        /** 识别会话互斥让位 */
        Yielding
    }

    enum class FailReason { Permission, Engine, Audio, Unknown }

    data class Snapshot(
        val status: Status = Status.Idle,
        val failReason: FailReason? = null,
        /** 当前状态进入时刻（elapsedRealtime），供看门狗宽限判定 */
        val statusSinceElapsedMs: Long = 0L,
        /** 监听循环最近一次心跳（elapsedRealtime），超时视为循环卡死 */
        val lastBeatElapsedMs: Long = 0L
    )

    private val _state = MutableStateFlow(Snapshot())

    val state: StateFlow<Snapshot> = _state

    val status: Status
        get() = _state.value.status

    /** 循环每轮心跳（监听时约 100ms 一次），锁内读改写防止丢更新 */
    @Synchronized
    fun beat() {
        _state.value = _state.value.copy(lastBeatElapsedMs = SystemClock.elapsedRealtime())
    }

    /** 状态迁移：仅在实际变化时写入；原因变化也视为迁移（重试阶段切换，宽限计时重置） */
    @Synchronized
    fun update(status: Status, reason: FailReason? = null) {
        val s = _state.value
        if (s.status == status && s.failReason == reason) return
        _state.value = s.copy(
            status = status,
            failReason = reason,
            statusSinceElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    /** 服务销毁/停止后归零 */
    @Synchronized
    fun reset() {
        _state.value = Snapshot(statusSinceElapsedMs = SystemClock.elapsedRealtime())
    }
}
