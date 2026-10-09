package org.autojs.autojs.core.image.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.autojs.autojs.runtime.api.ScreenMetrics;
import org.autojs.autojs.runtime.exception.ScriptInterruptedException;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Stardust on May 17, 2017.
 * Modified by SuperMonster003 as of Jan 11, 2026.
 */
// @Reference to Auto.js Pro 9.3.11 by SuperMonster003 on Dec 19, 2023.
public class ScreenCapturer {

    private static final Pattern PATTERN_BUFFER_FORMAT_EXCEPTION = Pattern.compile("buffer format ([0-9a-zA-Z]+) doesn't match");

    // Reduce total wait budget and use shorter waits to fail fast on abandoned BufferQueue.
    // zh-CN: 降低总等待预算并使用更短的等待粒度, 以在 BufferQueue 已被 abandoned 等场景下更快失败.
    private static final long CAPTURE_TOTAL_TIMEOUT_MS = 1200;

    // Use smaller wait slices for faster convergence.
    // zh-CN: 使用更小的等待切片以更快收敛.
    private static final long IMAGE_AVAILABLE_WAIT_SLICE_MS = 60;

    // Trigger self-healing earlier in the capture window.
    // zh-CN: 在 capture 窗口的更早阶段触发自愈.
    private static final long EARLY_HEALING_AT_MS = 600;

    // Use a dedicated thread for ImageReader callbacks to avoid deadlock when capture() blocks.
    // zh-CN: 使用独立线程处理 ImageReader 回调, 避免 capture() 阻塞时与回调线程相同导致的 "自锁式等待".
    private final HandlerThread mImageCallbackThread;
    private final Handler mImageCallbackHandler;

    // Listen to display changes even when AutoJs6 is in background (no foreground Activity).
    // zh-CN: 即使 AutoJs6 在后台 (无前台 Activity), 也通过 DisplayListener 监听显示变化, 避免配置事件丢失.
    private final DisplayManager mDisplayManager;
    private final DisplayManager.DisplayListener mDisplayListener;

    public static final int ORIENTATION_AUTO = Configuration.ORIENTATION_UNDEFINED; // 0
    public static final int ORIENTATION_LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE; // 1
    public static final int ORIENTATION_PORTRAIT = Configuration.ORIENTATION_PORTRAIT; // 2
    public static final int ORIENTATION_NONE = -1;

    private volatile Image mUnderUsingImage;
    private final int mScreenDensity;
    private final Handler mHandler;
    private final int mOrientation;
    private final Object mImageAvailableLock = new Object();
    private final Options mOptions;
    private ImageReader mImageReader;
    private MediaProjection mMediaProjection;
    // Callback registered on Android 14+ (required before createVirtualDisplay),
    // kept as a field so it can be unregistered exactly once in release().
    // zh-CN: Android 14+ 要求 createVirtualDisplay 前必须已注册回调, 保存引用以便 release 时反注册.
    private MediaProjection.Callback mMediaProjectionCallback;
    private VirtualDisplay mVirtualDisplay;
    private OnScreenCaptureAvailableListener mOnScreenCaptureAvailableListener;
    private int mDetectedOrientation;
    // @Bugfix on Oct 9, 2026: initialize to ORIENTATION_NONE so the very first
    // refreshVirtualDisplay(...isInit=true) call always wins and correctly assigns
    // mAppliedOrientation to the actual detected orientation. Otherwise the previous
    // initial value (ORIENTATION_AUTO == ORIENTATION_UNDEFINED == 0) would never match
    // any real orientation (PORTRAIT / LANDSCAPE), causing every subsequent capture()
    // to falsely detect "orientation changed" and recreate VirtualDisplay, tripping
    // Android 14+'s SecurityException ("Don't take multiple captures ...").
    //
    // zh-CN: 初始化为 ORIENTATION_NONE, 让首次 refreshVirtualDisplay(isInit=true) 总能
    // 把 mAppliedOrientation 正确赋值为真实方向. 否则旧的初值 (ORIENTATION_AUTO ==
    // ORIENTATION_UNDEFINED == 0) 永远不会等于真实方向 (PORTRAIT/LANDSCAPE),
    // 导致后续每次 capture() 都误判"方向变化"重建 VirtualDisplay, 触发 Android 14+
    // 的 SecurityException ("Don't take multiple captures ...").
    private int mAppliedOrientation = ORIENTATION_NONE;
    private int mPixelFormat = PixelFormat.RGBA_8888;
    private volatile boolean mImageAvailable = false;
    private boolean mShouldRefreshVirtualDisplayOnNextCapture = false;
    // Idempotency guard for release(): it may be invoked both explicitly on engine exit
    // and again later via finalize(); the second call must be a no-op.
    // zh-CN: release 幂等保护: 引擎退出时会显式释放, 之后 finalize 可能再次触发, 二次调用必须无效.
    private volatile boolean mReleased = false;

    // Set to false when the MediaProjection is stopped by the system / user (via onStop callback)
    // or when release() is called. Read by ScreenCaptureManager to decide whether the cached
    // capturer can be reused without re-popping the permission dialog.
    // zh-CN: 当 MediaProjection 被系统/用户停止 (onStop 回调) 或 release() 被调用时置为 false.
    // ScreenCaptureManager 据此判断缓存的 capturer 是否还能复用 (从而避免重复弹授权框).
    private volatile boolean mAvailable = true;

    public ScreenCapturer(Context context, Intent data, Options options, Handler handler) {
        mOptions = options;
        mHandler = handler;

        mImageCallbackThread = new HandlerThread("ScreenCapturer-ImageReader");
        mImageCallbackThread.start();
        mImageCallbackHandler = new Handler(mImageCallbackThread.getLooper());

        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);

        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                /* Ignored. */
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                /* Ignored. */
            }

            @Override
            public void onDisplayChanged(int displayId) {
                // Only care about DEFAULT_DISPLAY.
                // zh-CN: 只关心 DEFAULT_DISPLAY.
                if (displayId != Display.DEFAULT_DISPLAY) return;

                // Mark refresh for next capture to survive background state.
                // zh-CN: 标记下一次 capture 刷新, 用于后台状态下避免错过配置变化事件.
                refreshDetectedOrientation();
                if (mOptions.isAsync) {
                    mHandler.post(() -> refreshVirtualDisplay(mDetectedOrientation, false));
                } else {
                    mShouldRefreshVirtualDisplayOnNextCapture = true;
                }
            }
        };

        // Register on a non-blocking handler.
        // zh-CN: 使用不易被阻塞的 handler 注册监听.
        mDisplayManager.registerDisplayListener(mDisplayListener, mImageCallbackHandler);

        mMediaProjection = ((MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(Activity.RESULT_OK, (Intent) data.clone());
        mScreenDensity = options.density;
        mOrientation = options.orientation;

        refreshDetectedOrientation();
        refreshVirtualDisplay(mOrientation == ORIENTATION_AUTO ? mDetectedOrientation : mOrientation, true);

        EventBus.getDefault().register(this);
    }

    private void refreshDetectedOrientation() {
        // Prefer rotation over (w/h) for orientation detection.
        // zh-CN: 使用 rotation 而不是 (w/h) 判断方向.
        int rotation = ScreenMetrics.getRotation();
        mDetectedOrientation = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
    }

    private int getExpectedWidthByDetectedOrientation() {
        // Keep consistent with refreshVirtualDisplay() sizing logic.
        // zh-CN: 与 refreshVirtualDisplay() 的尺寸计算保持一致.
        return ScreenMetrics.getOrientationAwareScreenWidth(mDetectedOrientation);
    }

    private int getExpectedHeightByDetectedOrientation() {
        // Keep consistent with refreshVirtualDisplay() sizing logic.
        // zh-CN: 与 refreshVirtualDisplay() 的尺寸计算保持一致.
        return ScreenMetrics.getOrientationAwareScreenHeight(mDetectedOrientation);
    }

    public record Options(int width, int height, int orientation, int density, boolean isAsync) {
        @NonNull
        @Override
        public String toString() {
            return MessageFormat.format(
                    "Options'{'width={0}, height={1}, orientation={2}, density={3}, isAsync={4}'}'",
                    width, height, orientation, density, isAsync);
        }
    }

    public interface OnScreenCaptureAvailableListener {
        void onCaptureAvailable(Image image);
    }

    private Image acquireLatestImage(long deadlineUptimeMillis) {
        // Always wait for a fresh frame in sync mode.
        // zh-CN: 同步模式下每次都等待一帧新图.
        if (!mOptions.isAsync) {
            synchronized (mImageAvailableLock) {
                mImageAvailable = false;
            }
        }

        // Try several times but never exceed the given deadline.
        // zh-CN: 做有限次数重试, 但绝不超过 deadline 指定的总时间预算.
        for (int i = 0; i < 20; i++) {
            long now = SystemClock.uptimeMillis();
            if (now >= deadlineUptimeMillis) return null;

            long remain = deadlineUptimeMillis - now;
            waitForImageAvailable(Math.min(IMAGE_AVAILABLE_WAIT_SLICE_MS, remain));

            try {
                Image img = mImageReader.acquireLatestImage();
                if (img != null) return img;
            } catch (UnsupportedOperationException ex) {
                Integer pixelFormat = getPixelFormat(ex);
                if (pixelFormat != null) {
                    setPixelFormat(pixelFormat);
                    if (!mOptions.isAsync) {
                        synchronized (mImageAvailableLock) {
                            mImageAvailable = false;
                        }
                    }
                    continue;
                }
                throw ex;
            }

            // Reset and wait again.
            // zh-CN: 重置标记并继续等待下一帧.
            if (!mOptions.isAsync) {
                synchronized (mImageAvailableLock) {
                    mImageAvailable = false;
                }
            }
        }

        return null;
    }

    private void waitForImageAvailable(long timeoutMillis) {
        if (!mImageAvailable) {
            synchronized (mImageAvailableLock) {
                if (!mImageAvailable) {
                    try {
                        mImageAvailableLock.wait(timeoutMillis);
                    } catch (InterruptedException ex) {
                        throw new ScriptInterruptedException();
                    }
                }
            }
        }
    }

    @Nullable
    private Integer getPixelFormat(UnsupportedOperationException ex) {
        String message = ex.getMessage();
        if (message != null) {
            Matcher matcher = PATTERN_BUFFER_FORMAT_EXCEPTION.matcher(message);
            if (matcher.find()) {
                final String group = matcher.group(1);
                if (group != null && group.startsWith("0x")) {
                    return Integer.parseInt(group.substring(2), 16);
                }
            }
        }
        return null;
    }

    private void initVirtualDisplay(int width, int height, int screenDensity) {
        refreshImageReader(width, height);

        // Android 14+ requires a callback to be registered before createVirtualDisplay.
        // Register exactly once on the MAIN looper: the script servant looper may be
        // recycled (on engine exit) before MediaProjection#stop dispatches onStop,
        // which previously caused "sending message to a Handler on a dead thread" warnings.
        // zh-CN:
        // Android 14+ 要求 createVirtualDisplay 前必须已注册回调.
        // 必须只注册一次, 且挂载到主线程 Looper: 脚本 servant Looper 可能在
        // MediaProjection#stop 派发 onStop 之前已被回收 (引擎退出), 此前会导致死线程告警.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mMediaProjectionCallback == null) {
            mMediaProjectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // The projection was stopped by the system / user / another app. Mark the
                    // capturer as no longer available so ScreenCaptureManager will build a fresh
                    // session (and re-pop the permission dialog) on next request.
                    // zh-CN: 投影被系统/用户/其他应用停止. 标记 capturer 不可用, 让
                    // ScreenCaptureManager 下次申请时重建会话 (并重新弹授权框).
                    mAvailable = false;
                    release();
                }
            };
            mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));
        }

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                ScreenCapturer.class.getSimpleName(),
                width,
                height,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(),
                null,
                null
        );
    }

    private void refreshImageReader(int width, int height) {
        if (mImageReader != null) {
            mImageReader.close();
        }
        synchronized (mImageAvailableLock) {
            mImageAvailable = false;
        }
        int maxImages = mOptions.isAsync ? 1 : 3;
        mImageReader = ImageReader.newInstance(width, height, mPixelFormat, maxImages);

        // Always dispatch ImageReader callbacks on the dedicated thread.
        // zh-CN: 始终在独立线程分发 ImageReader 回调.
        setImageListener(mImageCallbackHandler);
    }

    private void refreshVirtualDisplay(int orientation, boolean isInit) {
        AtomicInteger width = new AtomicInteger();
        AtomicInteger height = new AtomicInteger();
        if (orientation == ORIENTATION_NONE) {
            width.set(mOptions.width);
            height.set(mOptions.height);
        } else {
            width.set(ScreenMetrics.getOrientationAwareScreenWidth(orientation));
            height.set(ScreenMetrics.getOrientationAwareScreenHeight(orientation));
        }

        // Recreate VirtualDisplay when orientation changes in AUTO mode.
        // VirtualDisplay.resize(...) may not reliably switch output orientation on some devices,
        // causing canvas size to mismatch content orientation.
        // zh-CN:
        // AUTO 模式下只要方向发生变化, 就直接重建 VirtualDisplay.
        // 部分设备上 VirtualDisplay.resize(...) 可能无法可靠切换输出方向, 从而导致画布尺寸与内容方向错配.
        //
        // @Bugfix on Oct 9, 2026:
        //  ! Android 14+ 对同一 MediaProjection 上 createVirtualDisplay 的调用次数有限制
        //  ! (抛 SecurityException "Don't take multiple captures ...").
        //  ! 之前 mAppliedOrientation 初始为 ORIENTATION_AUTO(0) 而 mDetectedOrientation 是
        //  ! PORTRAIT/LANDSCAPE, 导致每次 capture 都误判"方向变化"触发重建.
        //  ! 现在: 初始把 mAppliedOrientation 对齐到首次实际方向; AUTO 模式下仅在真实方向
        //  ! 发生改变时才重建, 否则仅做 resize/setSurface.
        boolean orientationChanged = orientation != mAppliedOrientation;
        boolean shouldRecreate = !isInit
                && mVirtualDisplay != null
                && mOrientation == ORIENTATION_AUTO
                && orientationChanged;

        if (mVirtualDisplay == null) {
            if (isInit) {
                initVirtualDisplay(width.get(), height.get(), mScreenDensity);
                mAppliedOrientation = orientation;
            }
            return;
        }

        if (shouldRecreate) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            initVirtualDisplay(width.get(), height.get(), mScreenDensity);
            mAppliedOrientation = orientation;
            return;
        }

        // Only touch surface/size when something actually changed; otherwise leave the
        // VirtualDisplay pipeline alone so we don't repeatedly trip Android 14+'s
        // "don't take multiple captures" SecurityException.
        // zh-CN: 仅在尺寸/方向真的变化时才动 surface/resize, 否则保持 VirtualDisplay 不动,
        // 避免反复触发 Android 14+ 的 "Don't take multiple captures" SecurityException.
        boolean sizeChanged = mImageReader == null
                || mImageReader.getWidth() != width.get()
                || mImageReader.getHeight() != height.get();
        if (sizeChanged || orientationChanged) {
            refreshImageReader(width.get(), height.get());
            mVirtualDisplay.setSurface(mImageReader.getSurface());
            mVirtualDisplay.resize(width.get(), height.get(), mScreenDensity);
            mAppliedOrientation = orientation;
        }
    }

    private void setImageListener(Handler handler) {
        ImageReader.OnImageAvailableListener o = mOptions.isAsync
                ? new OnImageAvailableListenerAsync(this)
                : new OnImageAvailableListenerSync(this);
        mImageReader.setOnImageAvailableListener(o, handler);
    }

    public void setImageListenerAsync(ImageReader imageReader) {
        var listener = mOnScreenCaptureAvailableListener;
        if (listener == null) return;
        try (Image img = imageReader.acquireLatestImage()) {
            // acquireLatestImage may return null (no available frames/competition/switching).
            // zh-CN: acquireLatestImage 可能返回 null (无可用帧/竞争/切换中).
            if (img == null) return;
            listener.onCaptureAvailable(img);
        } catch (IllegalStateException e) {
            // Ignore the current frame if ImageReader/VirtualDisplay is switching or closed.
            // zh-CN: 当 ImageReader/VirtualDisplay 正在切换或已关闭时可能抛出, 忽略本帧.
        }
    }

    public void setImageListenerSync(ImageReader imageReader) {
        // Always notify for the current ImageReader.
        // zh-CN: 只要回调来自当前 ImageReader, 就直接唤醒等待线程, 避免信号丢失.
        if (imageReader != mImageReader) return;
        synchronized (mImageAvailableLock) {
            mImageAvailable = true;
            mImageAvailableLock.notifyAll();
        }
    }

    private void setPixelFormat(int pixelFormat) {
        mPixelFormat = pixelFormat;
        refreshImageReader(mImageReader.getWidth(), mImageReader.getHeight());
        mVirtualDisplay.setSurface(mImageReader.getSurface());
    }

    // Last time (uptimeMillis) a capture() successfully returned a frame.
    // zh-CN: 上次 capture() 成功返回帧的时间戳.
    private volatile long mLastSuccessfulCaptureUptimeMillis = 0L;

    /**
     * Kick the VirtualDisplay pipeline by detaching and re-attaching the ImageReader surface.
     * Does NOT recreate the VirtualDisplay itself, so it doesn't count against Android 14+'s
     * "createVirtualDisplay calls per MediaProjection" budget.
     *
     * zh-CN: 通过脱离并重挂 ImageReader surface 来"踢"一下 VirtualDisplay 管线.
     * 不重建 VirtualDisplay 本体, 因此不占用 Android 14+ 的"每个 MediaProjection
     * 的 createVirtualDisplay 次数"预算.
     */
    private void kickVirtualDisplaySurface() {
        try {
            if (mVirtualDisplay == null || mImageReader == null) return;
            android.view.Surface surface = mImageReader.getSurface();
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay.setSurface(surface);
        } catch (Throwable ignored) {
            /* Best effort — if the VirtualDisplay is dead, isValid() will catch it next time. */
        }
    }

    @Nullable
    public Image capture() {
        if (mOptions.isAsync) {
            throw new IllegalStateException("capture() is not available in async mode");
        }

        final long start = SystemClock.uptimeMillis();
        final long deadline = start + CAPTURE_TOTAL_TIMEOUT_MS;

        // If the last successful capture was a long time ago (e.g. a different script was
        // running back then), the VirtualDisplay may be dormant. Kick it once before entering
        // the wait loop so we don't burn the whole timeout budget waiting for frames that
        // would never come on their own. Kicks are throttled so a healthy fast-path never
        // pays this cost.
        // zh-CN: 若距上次成功截屏已过较长时间 (例如之前是另一个脚本在跑), VirtualDisplay
        // 可能处于休眠状态. 进入等待循环前先踢一次, 避免把整个超时预算都耗在等永远
        // 不会来的帧上. 踢动有节流, 健康路径不会付出代价.
        final long STALE_THRESHOLD_MS = 1500;
        if (mLastSuccessfulCaptureUptimeMillis == 0
                || (start - mLastSuccessfulCaptureUptimeMillis) > STALE_THRESHOLD_MS) {
            kickVirtualDisplaySurface();
        }

        // For AUTO mode, do a best-effort self-check before acquiring the frame.
        // NOTE on Oct 9, 2026: only refresh when ORIENTATION changed, not on size jitter.
        // The previous "size mismatch" check could fire spuriously on devices where
        // ScreenMetrics reports slightly different values (with/without nav bar), causing
        // every capture() to recreate VirtualDisplay and trip Android 14+'s
        // "Don't take multiple captures" SecurityException.
        // zh-CN: AUTO 模式下, 在取帧之前做一次尽力自检.
        // 注意: 仅在方向真的变化时才刷新, 尺寸抖动不触发.
        // 之前的"尺寸不匹配"检查在 ScreenMetrics 返回值抖动 (含/不含导航栏) 的设备上
        // 会误触发, 导致每次 capture() 都重建 VirtualDisplay, 触发 Android 14+ 的
        // "Don't take multiple captures" SecurityException.
        if (mOrientation == ORIENTATION_AUTO) {
            int prevOrientation = mDetectedOrientation;
            refreshDetectedOrientation();
            if (mDetectedOrientation != prevOrientation) {
                refreshVirtualDisplay(mDetectedOrientation, false);
            }
        }

        if (mShouldRefreshVirtualDisplayOnNextCapture) {
            mShouldRefreshVirtualDisplayOnNextCapture = false;
            refreshVirtualDisplay(mDetectedOrientation, false);
        }

        // Retry a few times to skip transitional frames after resizing/switching,
        // but respect the total timeout budget.
        // zh-CN: 在 resize/切换后重试少量次数以跳过过渡帧, 但必须遵守总超时预算.
        for (int i = 0; i < 5; i++) {
            long now = SystemClock.uptimeMillis();
            if (now >= deadline) break;

            // Early self-healing in AUTO mode to avoid spending the whole budget waiting on a bad pipeline.
            // NOTE on Oct 9, 2026: only invoke refreshVirtualDisplay if orientation actually changed.
            // Previously this ran on every capture pass, which (when combined with size/orientation
            // mis-detection) would recreate VirtualDisplay multiple times per session, tripping
            // Android 14+'s "Don't take multiple captures" SecurityException.
            // zh-CN: AUTO 模式下尽早自愈. 注意: 仅在方向真的变化时才调 refreshVirtualDisplay.
            // 之前每次 capture 循环都会调, 叠加尺寸/方向误判时会话内多次重建 VirtualDisplay,
            // 触发 Android 14+ 的 "Don't take multiple captures" SecurityException.
            if (mOrientation == ORIENTATION_AUTO && (now - start) >= EARLY_HEALING_AT_MS) {
                int prevOrientation = mDetectedOrientation;
                refreshDetectedOrientation();
                if (mDetectedOrientation != prevOrientation) {
                    refreshVirtualDisplay(mDetectedOrientation, false);
                }
            }

            Image acquireLatestImage = acquireLatestImage(deadline);
            if (acquireLatestImage == null) continue;

            if (mOrientation == ORIENTATION_AUTO) {
                int expectedWidth = getExpectedWidthByDetectedOrientation();
                int expectedHeight = getExpectedHeightByDetectedOrientation();
                if (acquireLatestImage.getWidth() != expectedWidth || acquireLatestImage.getHeight() != expectedHeight) {
                    // Drop mismatched frame. Don't proactively refresh VirtualDisplay here:
                    // if we're seeing a mismatched frame it's likely a transient from a recent
                    // surface change, and another refresh would only compound the problem on
                    // Android 14+ (where createVirtualDisplay calls are rate-limited per
                    // MediaProjection instance).
                    // zh-CN: 丢弃尺寸不匹配的帧. 此处不主动刷新 VirtualDisplay:
                    // 看到不匹配帧多半是最近一次 surface 切换的过渡帧, 再刷一次只会在
                    // Android 14+ (MediaProjection 实例对 createVirtualDisplay 次数有限) 上
                    // 让问题更严重.
                    acquireLatestImage.close();
                    continue;
                }
            }

            if (mUnderUsingImage != null) {
                mUnderUsingImage.close();
            }
            mUnderUsingImage = acquireLatestImage;
            mLastSuccessfulCaptureUptimeMillis = SystemClock.uptimeMillis();
            return mUnderUsingImage;
        }

        // If timed out, DO NOT force-rebuild the VirtualDisplay. On Android 14+,
        // MediaProjection#createVirtualDisplay can only be called a limited number of times
        // per MediaProjection instance; calling it again on timeout throws
        // SecurityException ("Don't take multiple captures ...").
        //
        // Instead, only refresh the ImageReader surface on the SAME VirtualDisplay —
        // this is enough to recover from "BufferQueue abandoned" style bad states without
        // touching the MediaProjection pipeline.
        //
        // zh-CN: 超时后, 不要强制重建 VirtualDisplay. Android 14+ 对同一 MediaProjection
        // 实例上调 createVirtualDisplay 的次数有限制, 再调会抛 SecurityException
        // ("Don't take multiple captures ...").
        // 改为仅在同一个 VirtualDisplay 上刷新 ImageReader surface —— 这足以从
        // "BufferQueue abandoned" 等坏状态中自愈, 而不动 MediaProjection 管线.
        if (mOrientation == ORIENTATION_AUTO) {
            refreshDetectedOrientation();
            if (mVirtualDisplay != null && mImageReader != null) {
                int w = getExpectedWidthByDetectedOrientation();
                int h = getExpectedHeightByDetectedOrientation();
                if (mImageReader.getWidth() != w || mImageReader.getHeight() != h) {
                    refreshImageReader(w, h);
                    try {
                        mVirtualDisplay.setSurface(mImageReader.getSurface());
                        mVirtualDisplay.resize(w, h, mScreenDensity);
                        mAppliedOrientation = mDetectedOrientation;
                    } catch (Throwable ignored) {
                        /* VirtualDisplay may already be dead; nothing more we can do. */
                    }
                }
            }
            mShouldRefreshVirtualDisplayOnNextCapture = false;
        }

        // Do not return stale cached image when no valid frame is available.
        // zh-CN: 当无法获取到有效帧时, 不要返回旧缓存帧.
        return null;
    }

    public Options getOptions() {
        return mOptions;
    }

    /**
     * Expose the underlying MediaProjection so host bridges (e.g. Operit's
     * AutoJsScreenCaptureHostBridge) can read it without reflection.
     * May return null after release().
     *
     * zh-CN: 暴露底层 MediaProjection, 让宿主桥 (如 Operit 的 AutoJsScreenCaptureHostBridge)
     * 无需反射即可读取. release() 之后可能返回 null.
     */
    @Nullable
    public MediaProjection getMediaProjection() {
        return mMediaProjection;
    }

    /**
     * Whether the underlying MediaProjection is still alive (not stopped by the system,
     * not released by us). Used by ScreenCaptureManager to decide if this capturer can be
     * reused for a new capture request without re-popping the permission dialog.
     *
     * zh-CN: 底层 MediaProjection 是否仍然可用 (未被系统停止, 未被我们 release).
     * ScreenCaptureManager 据此判断当前 capturer 能否复用 (避免重复弹授权框).
     */
    public boolean isAvailable() {
        return mAvailable;
    }

    /**
     * Whether the capturer can serve a new capture request without re-authorization.
     *
     * <p>
     * NOTE on Oct 9, 2026: this intentionally mirrors AutoX's "available" semantics —
     * only check that the MediaProjection hasn't been stopped, NOT that the VirtualDisplay
     * is still valid. The previous strict check (display.getDisplay().isValid()) would
     * return false as soon as the script went to background or the display pipeline
     * momentarily invalidated the surface, even though the MediaProjection itself was
     * still perfectly usable (visible via `dumpsys media_projection`). This caused
     * cross-script reuse to fail and re-pop the permission dialog.
     *
     * zh-CN: 当前 capturer 是否能在不重新授权的情况下服务新的截屏请求.
     * 注意: 此处刻意对齐 AutoX 的 "available" 语义 —— 只检查 MediaProjection 是否未被
     * 停止, 不检查 VirtualDisplay 是否仍有效. 之前的严格检查 (display.getDisplay()
     * .isValid()) 会在脚本退到后台或显示管线临时失效时返回 false, 即便 MediaProjection
     * 本体仍完全可用 (dumpsys media_projection 可见). 这导致跨脚本复用失败,
     * 重复弹授权框.
     */
    public boolean isValid() {
        return mAvailable && mMediaProjection != null;
    }

    @Subscribe
    public void onConfigurationChanged(Configuration configuration) {
        if (mOrientation != ORIENTATION_AUTO) return;

        // Always schedule refresh for AUTO mode.
        // zh-CN: AUTO 模式下收到配置变化事件时总是安排下一次 capture 刷新.
        refreshDetectedOrientation();
        if (mOptions.isAsync) {
            mHandler.post(() -> refreshVirtualDisplay(mDetectedOrientation, false));
        } else {
            mShouldRefreshVirtualDisplayOnNextCapture = true;
        }
    }

    /**
     * Release all capture resources. Idempotent: repeated calls are no-ops.
     * zh-CN: 释放全部截屏资源. 幂等: 重复调用为无效操作.
     */
    public void release() {
        if (mReleased) return;
        mReleased = true;
        mAvailable = false;

        // Release the display pipeline first, then stop the projection itself.
        // Stopping the projection while a VirtualDisplay is still attached may leave
        // the pipeline in an inconsistent state (and can leak the last acquired frame).
        // zh-CN: 先释放显示管线 (VirtualDisplay/ImageReader/帧), 最后停止投屏本身.
        // 投屏仍在挂载 VirtualDisplay 时直接 stop 可能导致管线状态不一致 (并泄漏最后获取的帧).
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        if (mUnderUsingImage != null) {
            mUnderUsingImage.close();
            mUnderUsingImage = null;
        }
        if (mMediaProjection != null) {
            // Unregister the callback before stop() so the (empty) onStop dispatch
            // can never reach an already-torn-down handler.
            // zh-CN: stop() 之前先反注册回调, 避免 (空实现的) onStop 派发到已销毁的 handler.
            if (mMediaProjectionCallback != null) {
                mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                mMediaProjectionCallback = null;
            }
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        // Unregister display listener to avoid leaks.
        // zh-CN: 反注册 DisplayListener 以避免泄漏.
        mDisplayManager.unregisterDisplayListener(mDisplayListener);

        // Quit callback thread to avoid leaks.
        // zh-CN: 退出回调线程以避免泄漏.
        mImageCallbackThread.quitSafely();

        EventBus.getDefault().unregister(this);
    }

    public void setImageCaptureCallback(OnScreenCaptureAvailableListener onScreenCaptureAvailableListener) {
        mOnScreenCaptureAvailableListener = onScreenCaptureAvailableListener;
    }

    /**
     * Clear the registered capture callback only if it currently equals the given one.
     * Used when a ScriptRuntime exits so it detaches its own listener without clobbering
     * a newer runtime's listener that may have been attached after.
     *
     * zh-CN: 仅当当前注册的回调与传入回调相等时才清除. 用于 ScriptRuntime 退出时
     * 只摘掉自己的回调, 不会误清其他 (后注册的) runtime 回调.
     */
    public synchronized void unsetImageCaptureCallbackIfMatches(OnScreenCaptureAvailableListener listener) {
        if (listener != null && mOnScreenCaptureAvailableListener == listener) {
            mOnScreenCaptureAvailableListener = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

}