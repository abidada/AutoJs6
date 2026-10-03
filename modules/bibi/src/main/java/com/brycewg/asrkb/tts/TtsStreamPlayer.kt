/**
 * TTS 共享播放器：AudioTrack 流式播放 + 音频焦点 + 可打断。
 *
 * 从 OfflineSherpaTtsEngine 抽出，供本地离线与 CloneTTS HTTP 引擎共用：
 * - 非阻塞写入 + 停滞保护（WRITE_BLOCKING 在 cancel 暂停轨道后会永久阻塞）；
 * - drain 以「播放头连续 ~300ms 无进展」为播完判据（部分机型 stop 后直接丢缓冲，
 *   仅靠超时会每次白等满 DRAIN_TIMEOUT_MS）；
 * - 音频焦点：播报前申请瞬时 MAY_DUCK（压低媒体音），释放时交还。
 *
 * 线程契约：start/write/drain/release 仅由 TTS 工作线程串行调用；cancel 幂等、任意线程可调。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

internal class TtsStreamPlayer(
    context: Context,
    private val sampleRate: Int,
    private val channelCount: Int = 1
) {
    companion object {
        private const val TAG = "TtsStreamPlayer"

        /** 播完等待上限（正常短播报几秒内结束；超时兜底防止工作线程卡死） */
        private const val DRAIN_TIMEOUT_MS = 30_000

        /** 播放头连续无进展的判定轮数（6 × 50ms = 300ms 无进展即视为播完） */
        private const val DRAIN_STAGNANT_POLLS = 6

        /** 写入停滞保护上限（缓冲满且系统侧长时间不消费时抛错，防工作线程卡死） */
        private const val WRITE_STALL_TIMEOUT_MS = 5_000L
    }

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var cancelled: Boolean = false

    @Volatile private var track: AudioTrack? = null

    private var focusRequest: AudioFocusRequest? = null

    private var focusAcquired: Boolean = false

    /** 本轮已写入 AudioTrack 的帧数（用于播完等待，见 [drain]） */
    @Volatile private var writtenFrames: Long = 0L

    val isCancelled: Boolean get() = cancelled

    /** 申请瞬时音频焦点并创建启动轨道；失败抛异常由调用方兜底 */
    fun start() {
        focusAcquired = requestFocus()
        val t = buildTrack()
        track = t
        t.play()
    }

    /** 写入全部 PCM 样本（16bit LE）；cancelled 时静默退出 */
    fun write(shorts: ShortArray) {
        val t = track ?: throw IllegalStateException("AudioTrack not ready")
        var offset = 0
        // 非阻塞写入 + 轮询：WRITE_BLOCKING 在 cancel() 暂停轨道后会永久阻塞，
        // 非阻塞写配合 cancelled 检查保证打断能立刻从写循环里退出
        var stallMs = 0L
        while (offset < shorts.size && !cancelled) {
            val written = t.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
            if (written == 0) {
                // 缓冲满且长时间无进展（轨道被系统暂停/路由异常）：防永久卡死
                stallMs += 10L
                if (stallMs > WRITE_STALL_TIMEOUT_MS) {
                    throw IllegalStateException("AudioTrack write stalled (no drain in ${WRITE_STALL_TIMEOUT_MS}ms)")
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            stallMs = 0L
            offset += written
        }
        writtenFrames += shorts.size / channelCount.coerceAtLeast(1)
    }

    /**
     * 等待缓冲播完再返回：cancel() 后 playbackHeadPosition 追平写入帧数即播完。
     * onDone 必须晚于声音结束，否则开麦闸口会在尾音期间放行重开麦克风。
     */
    fun drain() {
        val t = track ?: return
        val total = writtenFrames
        if (total <= 0L) return
        try {
            t.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to drain audio track", e)
            return
        }
        var lastHead = -1L
        var stagnantPolls = 0
        var waited = 0
        while (!cancelled && waited < DRAIN_TIMEOUT_MS) {
            val head = try {
                t.playbackHeadPosition.toLong() and 0xffffffffL
            } catch (_: Throwable) {
                break
            }
            if (head >= total) break
            stagnantPolls = if (head == lastHead) stagnantPolls + 1 else 0
            if (stagnantPolls >= DRAIN_STAGNANT_POLLS) {
                Log.d(TAG, "Drain finished by head stall (head=$head total=$total)")
                break
            }
            lastHead = head
            try {
                Thread.sleep(50)
                waited += 50
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 打断：置位取消并暂停冲刷轨道；幂等，任意线程可调 */
    fun cancel() {
        cancelled = true
        try {
            track?.pause()
            track?.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop audio track", t)
        }
    }

    /** 释放轨道并交还音频焦点；幂等 */
    fun release() {
        val t = track
        track = null
        if (t != null) {
            try {
                t.stop()
            } catch (_: Throwable) {
            }
            try {
                t.release()
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed to release audio track", t2)
            }
        }
        abandonFocus()
    }

    private fun buildTrack(): AudioTrack {
        val channelMask = if (channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocus(): Boolean = try {
        val am = audioManager ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(audioAttrs)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to request tts audio focus", t)
        false
    }

    private fun abandonFocus() {
        try {
            val am = audioManager ?: return
            val request = focusRequest
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.abandonAudioFocusRequest(request)
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to abandon tts audio focus", t)
        }
    }
}
