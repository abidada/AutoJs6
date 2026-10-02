/**
 * 本地 TTS 引擎管理：懒加载、按键缓存、闲置自动卸载、预加载。
 *
 * 参考 asr 包 BaseSherpaOfflineRecognizerManager 的缓存/卸载模式，但保持
 * 独立实现（与 OfflineSpeechDenoiserManager 一致的自带锁风格）：TTS 加载
 * 发生在播报工作线程上，需要同步语义，不走异步的 LocalModelLoadCoordinator。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object OfflineTtsManager {
    private const val TAG = "OfflineTtsManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Any()

    @Volatile private var engine: OfflineSherpaTtsEngine? = null

    /** 当前已加载引擎对应的缓存键（模型目录+变体+线程数），变更时换新释放旧 */
    @Volatile private var loadedKey: String? = null

    @Volatile private var loadFailed: Boolean = false

    @Volatile private var unloadJob: Job? = null

    fun isLoaded(): Boolean = engine != null

    /** 打断当前播放（任意线程可调；volatile 置位 + AudioTrack pause 均线程安全） */
    fun stopPlaying() {
        try {
            engine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop tts playback", t)
        }
    }

    /**
     * 同步获取（必要时加载）引擎。仅可在 TTS 工作线程调用。
     * @return null 表示模型缺失或加载失败
     */
    internal fun loadSync(context: Context, prefs: Prefs): OfflineSherpaTtsEngine? {
        val variant = TtsLocalModelCatalog.normalizeVariant(prefs.ttsModelVariant)
        val numThreads = prefs.ttsNumThreads
        val key = "$variant/$numThreads"
        engine?.let { cached ->
            if (loadedKey == key) return cached
        }
        synchronized(loadLock) {
            engine?.let { cached ->
                if (loadedKey == key) return cached
            }
            if (loadFailed) return null
            val appContext = context.applicationContext
            val files = TtsLocalModelCatalog.findModelFiles(
                TtsLocalModelCatalog.modelDir(appContext, variant),
                variant
            ) ?: run {
                Log.w(TAG, "TTS model not installed (variant=$variant)")
                return null
            }
            unloadInternal("reload")
            val created = OfflineSherpaTtsEngine.create(appContext, files, numThreads)
            if (created == null) {
                loadFailed = true
                return null
            }
            engine = created
            loadedKey = key
            Log.i(TAG, "TTS engine loaded (variant=$variant, threads=$numThreads, sampleRate=${created.sampleRate})")
            return created
        }
    }

    fun unload() {
        unloadInternal("unload")
    }

    /** 播报结束后按 keep-alive 配置调度卸载：0=立即，-1=常驻，>0=闲置 N 分钟 */
    fun scheduleAutoUnloadIfIdle(keepAliveMinutes: Int) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            when {
                keepAliveMinutes == -1 -> return@launch
                keepAliveMinutes <= 0 -> unloadInternal("keep-alive-immediate")
                else -> {
                    delay(keepAliveMinutes * 60_000L)
                    unloadInternal("keep-alive-timeout")
                }
            }
        }
    }

    /** 初始化预加载：后台加载引擎，消除首次播报延迟；失败静默（播报时会重试） */
    fun preloadAsync(context: Context, prefs: Prefs) {
        if (!prefs.ttsEnabled) return
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = loadSync(context, prefs)
                if (loaded != null) {
                    // 预热：空跑一次最短合成，触发 espeak-ng/jieba 词库初始化
                    loaded.speak("好", prefs.ttsSpeed, object : TtsSpeakCallback {
                        override fun onDone() = Unit
                        override fun onError(message: String) {
                            Log.w(TAG, "TTS warm-up failed: $message")
                        }
                    })
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TTS preload failed", t)
            }
        }
    }

    private fun unloadInternal(reason: String) {
        synchronized(loadLock) {
            val current = engine ?: return
            engine = null
            loadedKey = null
            try {
                current.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release tts engine ($reason)", t)
            }
            Log.d(TAG, "TTS engine unloaded ($reason)")
        }
    }
}
