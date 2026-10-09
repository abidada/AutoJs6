package org.autojs.autojs.core.image.capture

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.util.Consumer
import org.autojs.autojs.runtime.api.ScriptPromiseAdapter
import java.lang.ref.WeakReference

/**
 * Application-level owner of the [ScreenCapturer] / MediaProjection session.
 *
 * <p>
 * Historically AutoJs6 bound the screen capturer lifecycle to [org.autojs.autojs.runtime.ScriptRuntime],
 * so every new script run had to pop the system MediaProjection permission dialog again.
 * This manager lifts the session to the application level (mirroring AutoX's
 * <code>ScreenCaptureManager</code>), so the permission dialog is shown only once per
 * process — subsequent scripts reuse the still-alive MediaProjection and never see a dialog.
 *
 * <p>
 * Lifecycle:
 * <ul>
 *   <li>First call to {@link #requestScreenCapture} → pops system dialog, creates MediaProjection,
 *       starts {@link ScreenCapturerForegroundService}, builds a [ScreenCapturer], caches it in
 *       {@link #screenCapturer}.</li>
 *   <li>Subsequent calls → if {@code screenCapturer.isValid()} is true, only orientation is
 *       re-applied, no dialog.</li>
 *   <li>{@link #recycle} (via JS {@code images.stopScreenCapturer()} or the notification's
 *       "停止截图" action, or MediaProjection.Callback.onStop) → tears down the session so the
 *       next request will show the dialog again.</li>
 * </ul>
 *
 * <p>
 * zh-CN: 应用级 ScreenCapturer / MediaProjection 会话持有者.
 * 历史实现把截屏会话绑定在 ScriptRuntime 上, 每次跑脚本都要重新弹系统授权框.
 * 本管理器把会话提升到应用级 (对齐 AutoX 的 ScreenCaptureManager),
 * 让授权框每进程只出现一次 —— 后续脚本复用仍在运行的 MediaProjection, 不再弹框.
 *
 * Created by SuperMonster003 (original ScreenCapturer / ScreenCaptureRequester).
 * Refactored to application-scope singleton, modeled after AutoX's ScreenCaptureManager.
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_RETRY_ON_PROJECTION_EXPIRED = 3
    }

    @Volatile
    var screenCapturer: ScreenCapturer? = null
        private set

    /**
     * Whether the currently cached capturer can serve new capture requests without
     * popping the permission dialog again.
     *
     * zh-CN: 当前缓存的 capturer 是否能直接复用 (无需再次弹授权框).
     */
    fun isCapturerAvailable(): Boolean {
        val capturer = screenCapturer ?: return false
        return runCatching { capturer.isValid() }.getOrDefault(false)
    }

    /**
     * Request a screen capture session. If a previous session is still alive, this is a no-op
     * (other than re-applying orientation/async callback wiring).
     *
     * @param isAsync        whether the session is for async (event-stream) capture
     * @param handler        handler used by the underlying [ScreenCapturer]
     * @param attachCallback invoked with the live [ScreenCapturer] once available so the caller
     *                       can attach its per-runtime image listener
     *
     * zh-CN: 申请一次截屏会话. 若已有活着的会话则直接复用 (不弹授权框).
     */
    fun requestScreenCapture(
        context: Context,
        options: ScreenCapturer.Options,
        isAsync: Boolean,
        handler: Handler,
        attachCallback: Consumer<ScreenCapturer>,
    ): ScriptPromiseAdapter {
        val promiseAdapter = ScriptPromiseAdapter()

        // Fast path: an existing session is still valid — reuse it without re-prompting.
        //
        // NOTE on Oct 9, 2026: we must NOT resolve the promise synchronously here.
        // AutoJs6's synchronous requestScreenCapture() uses Rhino's ResultAdapter.wait(),
        // which converts the ScriptPromiseAdapter into a JS Promise and blocks the script
        // thread waiting for the .then() callback to fire. The Rhino Promise polyfill
        // dispatches .then() callbacks via setImmediate, which is bound to the script's
        // servant Looper (the SAME thread that's about to block). If we resolve the
        // promise on the script thread itself, the setImmediate queue never gets pumped
        // and the wait deadlocks.
        //
        // We therefore resolve on a DIFFERENT thread — the main looper — giving the
        // script's servant Looper a chance to enter its blocked state first, then
        // have the resolution delivered asynchronously.
        //
        // zh-CN: 快速路径 —— 已有活着的会话, 直接复用, 不再弹授权框.
        // 注意: 此处不能同步 resolve promise. AutoJs6 的同步 requestScreenCapture()
        // 走 Rhino 的 ResultAdapter.wait(), 它会把 ScriptPromiseAdapter 转成 JS Promise
        // 并阻塞脚本线程等 .then() 回调. Rhino 的 Promise polyfill 通过 setImmediate
        // 派发 .then() 回调, setImmediate 绑定在脚本的 servant Looper 上 (也就是即将
        // 阻塞的那个线程). 如果我们在脚本线程上同步 resolve, setImmediate 队列永远
        // 没机会泵, wait 会死锁.
        // 因此我们在另一个线程 (主线程 Looper) 上异步 resolve, 让脚本的 servant
        // Looper 先进入阻塞状态, 再由主线程异步派发 resolve.
        val existing = screenCapturer
        if (existing != null && runCatching { existing.isValid() }.getOrDefault(false)) {
            Log.d(TAG, "Reusing existing ScreenCapturer (no permission dialog)")
            // Pre-wire the capturer to the runtime on the CALLING thread (the script thread
            // about to block). This must happen before we hand control back so the script
            // can safely call captureScreen() right after requestScreenCapture() returns.
            // zh-CN: 在调用线程 (即将阻塞的脚本线程) 上先把 capturer 接到 runtime.
            // 这必须在交还控制权之前完成, 这样脚本在 requestScreenCapture() 返回后
            // 立刻调 captureScreen() 是安全的.
            attachCallback.accept(existing)
            Handler(Looper.getMainLooper()).post {
                try {
                    promiseAdapter.resolve(true)
                } catch (t: Throwable) {
                    promiseAdapter.reject(t)
                }
            }
            return promiseAdapter
        }

        // Slow path: tear down any stale capturer, pop the system dialog, build a fresh session.
        // zh-CN: 慢速路径 —— 清掉残留 capturer, 弹系统授权框, 建立新会话.
        recycle()

        val weakManager = WeakReference(this)
        var retryCount = 0

        val requester = ScreenCaptureRequester()
        lateinit var callback: ScreenCaptureRequester.Callback
        callback = object : ScreenCaptureRequester.Callback {
            override fun onRequestResult(resultCode: Int, intent: Intent?) {
                val manager = weakManager.get() ?: run {
                    promiseAdapter.resolve(false)
                    return
                }
                if (resultCode == android.app.Activity.RESULT_OK && intent != null) {
                    try {
                        val newCapturer = ScreenCapturer(context.applicationContext, intent, options, handler)
                        manager.screenCapturer = newCapturer
                        // Hand the live MediaProjection to the FGS before anything else, so its
                        // notification's "停止截图" action can actually stop the projection.
                        // Without this the service only stops its own shell and the projection
                        // stays alive (observed on device: notification action did nothing).
                        // zh-CN: 把活的 MediaProjection 交给前台服务, 让通知栏「停止截图」
                        // action 能真正停掉投影. 不这么做的话 service 只是停掉自己的壳,
                        // 投影仍然存活 (真机验证时观察到: 点通知 action 无效果).
                        newCapturer.mediaProjection?.let {
                            ScreenCapturerForegroundService.setMediaProjection(context, it)
                        }
                        attachCallback.accept(newCapturer)
                        promiseAdapter.resolve(true)
                    } catch (e: SecurityException) {
                        // Android 14+ can throw "non-current" if the grant token has been invalidated
                        // (e.g. another projection app grabbed the screen). Retry a few times by
                        // re-popping the dialog before giving up.
                        // zh-CN: Android 14+ 在授权 token 失效时会抛 "non-current". 重试若干次后再放弃.
                        val nonCurrent = e.message?.contains("non-current") == true
                        if (nonCurrent && retryCount < MAX_RETRY_ON_PROJECTION_EXPIRED) {
                            retryCount++
                            Log.w(TAG, "MediaProjection token expired, retrying ($retryCount/$MAX_RETRY_ON_PROJECTION_EXPIRED)")
                            Handler(Looper.getMainLooper()).postDelayed({
                                manager.doRequest(context, options, handler, attachCallback, callback)
                            }, 100)
                        } else {
                            Log.e(TAG, "Failed to create ScreenCapturer", e)
                            promiseAdapter.reject(e)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to create ScreenCapturer", e)
                        promiseAdapter.reject(e)
                    } finally {
                        // The ServiceConnection from ScreenCaptureRequester was only needed to
                        // wait for the FGS to come up before getMediaProjection(). Leaving it
                        // bound forever would keep the service alive (BIND_AUTO_CREATE) and make
                        // both stopSelf() and stopService() unable to destroy it — meaning the
                        // "停止截图" notification action could never fully release the session.
                        // zh-CN: ScreenCaptureRequester 的 ServiceConnection 只为在
                        // getMediaProjection() 前等 FGS 就绪. 若一直不 unbind, 服务会因
                        // BIND_AUTO_CREATE 被永久持有, stopSelf()/stopService() 都无法销毁,
                        // 通知栏「停止截图」便无法真正释放会话.
                        runCatching { requester.unbindService() }
                    }
                } else {
                    runCatching { requester.unbindService() }
                    promiseAdapter.resolve(false)
                }
            }

            override fun onRequestError(t: Throwable) {
                runCatching { requester.unbindService() }
                promiseAdapter.reject(t)
            }
        }

        doRequest(context, options, handler, attachCallback, callback, requester)
        return promiseAdapter
    }

    private fun doRequest(
        context: Context,
        options: ScreenCapturer.Options,
        handler: Handler,
        attachCallback: Consumer<ScreenCapturer>,
        callback: ScreenCaptureRequester.Callback,
        existingRequester: ScreenCaptureRequester? = null,
    ) {
        val requester = existingRequester ?: ScreenCaptureRequester()
        // Reuse the current foreground Activity when possible so the permission dialog appears
        // anchored to a visible window; otherwise StartForResultActivity is started transparently.
        // zh-CN: 优先复用前台 Activity 发起授权, 无前台时走透明的 StartForResultActivity.
        val requestContext = context
        requester.request(requestContext, callback)
    }

    /**
     * Stop and release the active capture session, if any. After this call, the next
     * [requestScreenCapture] will pop the system permission dialog again.
     *
     * zh-CN: 停止并释放当前截屏会话. 调用后, 下一次 requestScreenCapture 会重新弹授权框.
     */
    @Synchronized
    fun recycle() {
        val capturer = screenCapturer
        screenCapturer = null
        if (capturer != null) {
            runCatching { capturer.release() }
                .onFailure { Log.w(TAG, "Error releasing ScreenCapturer", it) }
        }
    }
}
