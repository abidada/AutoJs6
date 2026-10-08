package com.ai.assistance.operit.hostcompat

import android.content.Context
import android.os.Process
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.AppLogger

/**
 * Host-side initialization entry for the Operit module (P1.4).
 *
 * Called exactly once from the host `App.onCreate()` (wiring point D-3). The upstream
 * `OperitApplication` is NOT used as an Application (library manifests can't declare one);
 * its startup side-effects were either trimmed (accessibility pre-bind C16, virtual-screen
 * client env, personalized wake) or remain the module's own lazily-initialized singletons.
 *
 * Process guard: the host runs `:background` / `:crash_report` processes; Operit singletons
 * (JS engines, Room, ObjectBox, workflow schedulers) must only initialize in the main process.
 */
object OperitLibrary {

    private const val TAG = "OperitLibrary"

    /** Upstream Application FQCN; kept in one place for the reflective bootstrap paths. */
    private const val OPERIT_APPLICATION_CLASS =
        "com.ai.assistance.operit.core.application.OperitApplication"

    @Volatile
    private var initialized = false

    /** True once [init] completed in this process. */
    @JvmStatic
    var isInitialized: Boolean = false
        private set

    /**
     * Host accessibility backend (P4.1, C16). The host injects a concrete
     * [HostAccessibilityBackend] before/alongside [init]; until then every
     * [LocalAccessibilityProvider] call degrades to empty/false — the same
     * graceful behavior as the removed provider-APK architecture.
     */
    @Volatile
    @JvmStatic
    var accessibilityBackend: HostAccessibilityBackend? = null
        private set

    /** Injects the host accessibility backend (bibi `ScriptHost.bridge` precedent). */
    @JvmStatic
    fun setAccessibilityBackend(backend: HostAccessibilityBackend) {
        accessibilityBackend = backend
        AppLogger.i(TAG, "Host accessibility backend registered")
    }

    /**
     * Host screen-capture session backend (P4.2, C17); null degrades to "unavailable".
     */
    @Volatile
    @JvmStatic
    var screenCaptureBackend: HostScreenCaptureBackend? = null
        private set

    /** Injects the host screen-capture backend. */
    @JvmStatic
    fun setScreenCaptureBackend(backend: HostScreenCaptureBackend) {
        screenCaptureBackend = backend
        AppLogger.i(TAG, "Host screen-capture backend registered")
    }

    /**
     * Host speech (STT/TTS) backend (P4.3, C15). The host maps this onto bibi's ASR/TTS
     * orchestrators; null keeps [BibiSpeechBridge] fully degraded (initialize=false /
     * speak=false), so Operit's speech UI renders its own "unavailable" states.
     */
    @Volatile
    @JvmStatic
    var speechBackend: HostSpeechBackend? = null
        private set

    /** Injects the host speech backend. */
    @JvmStatic
    fun setSpeechBackend(backend: HostSpeechBackend) {
        speechBackend = backend
        AppLogger.i(TAG, "Host speech backend registered")
    }

    /**
     * Library-mode replacement for `(application as OperitApplication).initializeMainApplication()`
     * (P4.4). In the host process `application` is the host's own Application, so the upstream
     * hard cast threw ClassCastException. This resolves the reflectively-bootstrapped
     * `OperitApplication.instance`, lazily bootstrapping it if [init] has not run yet (e.g. the
     * module's UI is opened from a code path that skipped the `App.onCreate` wiring), and then
     * invokes `initializeMainApplication()` — which is idempotent upstream.
     *
     * Never throws: a failed bootstrap degrades to a no-op so the Operit UI can still render its
     * own error/permission screens rather than crashing the host on entry.
     */
    @Volatile
    private var bootstrapAttempted = false

    private val bootstrapLock = Any()

    /**
     * Lazily bootstraps the upstream OperitApplication on first use (Q2 / D-10).
     *
     * Cold-start `init` no longer bootstraps — it only wires process guards + the three
     * host backends. The first entry into any Operit surface (MainActivity / the two
     * Services) funnels through here and pays the one-time bootstrap cost, so users who
     * never open Operit never pay for ToolPkg scans, Room preload, or scheduler polling.
     */
    @JvmStatic
    fun ensureMainApplicationInitialized() {
        try {
            if (!isOperitApplicationReady()) {
                val host = hostContextRef ?: run {
                    AppLogger.w(TAG, "ensureMainApplicationInitialized: no host context; skip")
                    return
                }
                // 双重检查锁防并发重复 bootstrap(多入口同时首开时)。
                if (!bootstrapAttempted) {
                    synchronized(bootstrapLock) {
                        if (!bootstrapAttempted && !isOperitApplicationReady()) {
                            bootstrapAttempted = true
                            bootstrapOperitApplication(host)
                        }
                    }
                }
            }
            if (!isOperitApplicationReady()) {
                AppLogger.w(TAG, "ensureMainApplicationInitialized: OperitApplication unavailable; skip")
                return
            }
            OperitApplication.instance.initializeMainApplication()
        } catch (e: Throwable) {
            // 业务初始化失败：降级静默,UI 仍可渲染自身错误页。
            AppLogger.e(TAG, "ensureMainApplicationInitialized failed (UI continues)", e)
        }
    }

    /** Host application context captured in [init]; used for lazy (re)bootstrap. */
    @Volatile
    private var hostContextRef: Context? = null

    fun init(host: Context) {
        check(!initialized) { "OperitLibrary.init must be called only once per process" }
        initialized = true
        hostContextRef = host.applicationContext

        val processName = currentProcessName(host)
        if (processName.contains(":")) {
            AppLogger.d(TAG, "Skip Operit init in non-main process: $processName")
            return
        }

        // Q2 / D-10: 冷启动不再同步 bootstrapOperitApplication;推迟到首次
        // ensureMainApplicationInitialized(打开 Operit UI/服务)时按需引导。
        AppLogger.i(TAG, "Operit module initialized (main process, lazy bootstrap)")
        isInitialized = true
    }

    /**
     * Library-mode replacement for the upstream `OperitApplication.onCreate` (P4.4).
     *
     * Upstream Operit ships `OperitApplication` as its manifest `<application android:name>`;
     * in library form the host `App : MultiDexApplication` owns the process, so the module
     * cannot be declared as the Application. Instead we instantiate it reflectively and
     * attach the host context via `attachBaseContext`, then run the same `onCreate` +
     * `initializeMainApplication` chain. `OperitApplication.instance` is `lateinit` and is
     * referenced from ~21 module call sites (preferences, converters, toolbox/ToolPkg hooks),
     * so it must be populated in every process that touches those paths.
     *
     * Only the main process (guarded above) bootstraps; `:background` / `:crash_report`
     * stays untouched so no scheduler/Room/ObjectBox singleton escapes into a worker process.
     */
    private fun bootstrapOperitApplication(host: Context) {
        if (isOperitApplicationReady()) {
            AppLogger.d(TAG, "OperitApplication already bootstrapped; skip")
            return
        }
        try {
            val applicationClass =
                Class.forName(OPERIT_APPLICATION_CLASS) as Class<out android.app.Application>
            // Application 子类由框架创建时会走同一路径：no-arg ctor + attachBaseContext。
            val application = applicationClass.getDeclaredConstructor().newInstance()
            // attachBaseContext 为 ContextWrapper 的 protected 方法，反射调用（框架同路径）。
            val attach = android.content.ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
            attach.invoke(application, host.applicationContext)

            applicationClass.getMethod("onCreate").invoke(application)
            applicationClass.getMethod("initializeMainApplication").invoke(application)
            AppLogger.i(TAG, "OperitApplication bootstrapped in library mode")
        } catch (e: Throwable) {
            // 启动链失败不应带崩宿主；Operit 界面打开时会再次尝试（initializeMainApplication 幂等）。
            AppLogger.e(TAG, "OperitApplication bootstrap failed (host continues)", e)
        }
    }

    /**
     * True once [OperitApplication.instance] has been populated (bootstrap ran).
     *
     * Direct same-module reference (no reflection): `instance` is a companion `lateinit var`
     * whose backing field is private-static on the outer class and whose getter lives on the
     * Companion — neither is reachable via `getField("instance")`/`getMethod("getInstance")`
     * on the outer class (both threw at runtime, breaking the idempotence guard). Reading the
     * lateinit before init throws UninitializedPropertyAccessException, which runCatching maps
     * to "not ready".
     */
    private fun isOperitApplicationReady(): Boolean =
        runCatching { OperitApplication.instance }.isSuccess

    private fun currentProcessName(context: Context): String {
        return try {
            val pid = Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: context.packageName
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to resolve process name", e)
            context.packageName
        }
    }
}
