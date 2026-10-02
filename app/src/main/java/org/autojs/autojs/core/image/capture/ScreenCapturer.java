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
    private int mAppliedOrientation = ORIENTATION_AUTO;
    private int mPixelFormat = PixelFormat.RGBA_8888;
    private volatile boolean mImageAvailable = false;
    private boolean mShouldRefreshVirtualDisplayOnNextCapture = false;
    // Idempotency guard for release(): it may be invoked both explicitly on engine exit
    // and again later via finalize(); the second call must be a no-op.
    // zh-CN: release 幂等保护: 引擎退出时会显式释放, 之后 finalize 可能再次触发, 二次调用必须无效.
    private volatile boolean mReleased = false;

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
                /* Empty body. */
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
        boolean shouldRecreate = !isInit
                && mVirtualDisplay != null
                && mOrientation == ORIENTATION_AUTO
                && orientation != mAppliedOrientation;

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

        refreshImageReader(width.get(), height.get());
        mVirtualDisplay.setSurface(mImageReader.getSurface());
        mVirtualDisplay.resize(width.get(), height.get(), mScreenDensity);
        mAppliedOrientation = orientation;
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

    @Nullable
    public Image capture() {
        if (mOptions.isAsync) {
            throw new IllegalStateException("capture() is not available in async mode");
        }

        final long start = SystemClock.uptimeMillis();
        final long deadline = start + CAPTURE_TOTAL_TIMEOUT_MS;

        // For AUTO mode, do a best-effort self-check before acquiring the frame.
        // zh-CN: AUTO 模式下, 在取帧之前做一次尽力自检, 发现画布尺寸不匹配则主动刷新 VirtualDisplay.
        if (mOrientation == ORIENTATION_AUTO) {
            refreshDetectedOrientation();

            int expectedWidth = getExpectedWidthByDetectedOrientation();
            int expectedHeight = getExpectedHeightByDetectedOrientation();

            // ImageReader size represents the "canvas" size of VirtualDisplay.
            // zh-CN: ImageReader 尺寸代表 VirtualDisplay 的 "画布" 尺寸.
            if (mImageReader != null
                    && (mImageReader.getWidth() != expectedWidth || mImageReader.getHeight() != expectedHeight)) {
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
            // zh-CN: AUTO 模式下尽早自愈, 避免把整个预算都耗在一个已失效的管线 (如 BufferQueue abandoned) 上.
            if (mOrientation == ORIENTATION_AUTO && (now - start) >= EARLY_HEALING_AT_MS) {
                refreshDetectedOrientation();
                refreshVirtualDisplay(mDetectedOrientation, false);
            }

            Image acquireLatestImage = acquireLatestImage(deadline);
            if (acquireLatestImage == null) continue;

            if (mOrientation == ORIENTATION_AUTO) {
                int expectedWidth = getExpectedWidthByDetectedOrientation();
                int expectedHeight = getExpectedHeightByDetectedOrientation();
                if (acquireLatestImage.getWidth() != expectedWidth || acquireLatestImage.getHeight() != expectedHeight) {
                    // Drop mismatched frame and refresh display once more.
                    // zh-CN: 丢弃尺寸不匹配的帧, 并再次刷新 display.
                    acquireLatestImage.close();
                    refreshVirtualDisplay(mDetectedOrientation, false);
                    continue;
                }
            }

            if (mUnderUsingImage != null) {
                mUnderUsingImage.close();
            }
            mUnderUsingImage = acquireLatestImage;
            return mUnderUsingImage;
        }

        // If timed out, force a best-effort rebuild once to recover from "no-frame" bad state.
        // zh-CN: 若超时, 尝试强制重建一次以从 "无帧" 坏状态中自愈.
        if (mOrientation == ORIENTATION_AUTO) {
            refreshDetectedOrientation();
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            initVirtualDisplay(getExpectedWidthByDetectedOrientation(), getExpectedHeightByDetectedOrientation(), mScreenDensity);
            mAppliedOrientation = mDetectedOrientation;
            mShouldRefreshVirtualDisplayOnNextCapture = false;
        }

        // Do not return stale cached image when no valid frame is available.
        // zh-CN: 当无法获取到有效帧时, 不要返回旧缓存帧.
        return null;
    }

    public Options getOptions() {
        return mOptions;
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

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

}