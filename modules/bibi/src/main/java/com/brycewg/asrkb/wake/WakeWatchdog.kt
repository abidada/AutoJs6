/**
 * 语音唤醒服务看门狗：开关开启期间保证唤醒服务处于健康监听状态，异常时按退避静默重启。
 *
 * - 入口幂等：由保活服务周期协程（主宿主）与悬浮球服务/特权保活 Job/设置页/开机广播（事件驱动）调用；
 * - 健康判定：Listening 且心跳新鲜；Starting/Retrying/Yielding 属自愈路径，仅超宽限后才强制重启；
 * - 重启节流：1min → 3min → 5min → 10min（封顶），恢复健康后清零；多宿主共用同一节流状态；
 * - 全程静默，仅写 DebugLog（wake_watchdog 通道，仅动作时记录）。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

object WakeWatchdog {

    private const val TAG = "WakeWatchdog"
    private const val CHANNEL = "wake_watchdog"

    /** Listening 下心跳过期阈值：循环卡死判定 */
    private const val BEAT_STALE_MS = 10_000L

    /** 自愈态宽限：超时才强制重启，避免打断正常识别会话与自愈重试 */
    private const val STARTING_GRACE_MS = 60_000L
    private const val RETRYING_GRACE_MS = 300_000L
    private const val YIELDING_GRACE_MS = 600_000L

    /** 重启退避序列 */
    private val RESTART_BACKOFF_MS = longArrayOf(60_000L, 180_000L, 300_000L, 600_000L)

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var lastRestartElapsedMs = 0L

    @Volatile
    private var everRestarted = false

    /**
     * 健康检查 + 按需重启。可在任意线程调用（含主线程），内部不做长阻塞操作。
     */
    fun ensure(context: Context) {
        try {
            if (!Prefs(context).wakeWordEnabled) return

            val snap = WakeServiceState.state.value
            val now = SystemClock.elapsedRealtime()

            if (snap.status == WakeServiceState.Status.Listening &&
                now - snap.lastBeatElapsedMs <= BEAT_STALE_MS
            ) {
                restartAttempts = 0
                return
            }

            val reason = when (snap.status) {
                WakeServiceState.Status.Listening -> "heartbeat_stale"

                WakeServiceState.Status.Starting ->
                    if (now - snap.statusSinceElapsedMs > STARTING_GRACE_MS) "starting_grace_exceeded" else return

                WakeServiceState.Status.Retrying ->
                    if (now - snap.statusSinceElapsedMs > RETRYING_GRACE_MS) "retrying_grace_exceeded" else return

                WakeServiceState.Status.Yielding ->
                    if (now - snap.statusSinceElapsedMs > YIELDING_GRACE_MS) "yielding_grace_exceeded" else return

                WakeServiceState.Status.Idle -> "service_not_running"
            }

            if (everRestarted) {
                val backoff = RESTART_BACKOFF_MS[
                    restartAttempts.coerceIn(0, RESTART_BACKOFF_MS.lastIndex)
                ]
                if (now - lastRestartElapsedMs < backoff) return
            }

            restartAttempts += 1
            lastRestartElapsedMs = now
            everRestarted = true

            DebugLogManager.logPersistent(
                context,
                CHANNEL,
                "restart",
                mapOf(
                    "reason" to reason,
                    "attempt" to restartAttempts,
                    "status" to snap.status.name
                )
            )

            // 服务存活时先停后起，确保卡死的循环线程与音频流被完全回收；未运行则直接拉起
            if (snap.status != WakeServiceState.Status.Idle) {
                try {
                    WakeWordService.stop(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to dispatch wake stop before restart", t)
                    try {
                        context.stopService(Intent(context, WakeWordService::class.java))
                    } catch (_: Throwable) {
                    }
                }
            }
            WakeWordService.start(context)
        } catch (t: Throwable) {
            Log.w(TAG, "wake watchdog ensure failed", t)
        }
    }
}
