package com.brycewg.asrkb.clipboard

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ClipboardSyncOwner {
    data object Direct : ClipboardSyncOwner
}

/**
 * App 本体拥有的 Clipboard Sync Runtime Service。
 *
 * 不属于悬浮球。系统剪贴板 Direct 观察由此承载；凭证与网络只留在本进程。
 */
class ClipboardSyncRuntimeService : Service() {
    companion object {
        private const val TAG = "ClipboardSyncRuntime"

        const val ACTION_ACTIVATE_DIRECT =
            "com.brycewg.asrkb.clipboard.action.ACTIVATE_DIRECT"
        const val ACTION_DEACTIVATE_DIRECT =
            "com.brycewg.asrkb.clipboard.action.DEACTIVATE_DIRECT"
        const val ACTION_CONFIG_CHANGED =
            "com.brycewg.asrkb.clipboard.action.CONFIG_CHANGED"

        @Volatile
        private var runningInstance: ClipboardSyncRuntimeService? = null

        fun activateDirect(context: Context) {
            start(context, ACTION_ACTIVATE_DIRECT)
        }

        fun deactivateDirect(context: Context) {
            start(context, ACTION_DEACTIVATE_DIRECT)
        }

        fun notifyConfigChanged(context: Context) {
            start(context, ACTION_CONFIG_CHANGED)
        }

        fun downloadFile(entryId: String): Boolean = runningInstance?.runtime?.downloadFile(entryId) ?: false

        private fun start(context: Context, action: String) {
            context.applicationContext.startService(
                Intent(context.applicationContext, ClipboardSyncRuntimeService::class.java)
                    .setAction(action)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private var runtime: ClipboardSyncRuntime? = null
    private var owner: ClipboardSyncOwner? = null
    private var capabilityProbeJob: Job? = null
    private var screenReceiverRegistered = false
    private var imeChangeReceiverRegistered = false
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                if (prefs.syncClipboardRealtimeSupported == null) restartCapabilityProbe()
                runtime?.onNetworkAvailable()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    runtime?.onScreenOff()
                    if (runtime?.phase == ClipboardSyncRuntimePhase.INACTIVE) {
                        owner = null
                    }
                    maybeStopSelf()
                }
                Intent.ACTION_SCREEN_ON -> runtime?.onScreenOn()
            }
        }
    }

    private val imeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_INPUT_METHOD_CHANGED) return
            val current = try {
                Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/')
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to resolve IME after change", t)
                null
            }
            val expectedIme = when (owner) {
                ClipboardSyncOwner.Direct -> packageName
                null -> null
            }
            if (expectedIme != null && current != expectedIme) {
                runtime?.forceDeactivateSession()
                owner = null
            }
            if (runtime?.phase == ClipboardSyncRuntimePhase.INACTIVE) {
                owner = null
            }
            maybeStopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        runningInstance = this
        ensureRuntime()
        ensureCapabilityProbe()
        registerScreenReceiver()
        registerImeChangeReceiver()
        registerNetworkCallback()
        Log.d(TAG, "Runtime service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureRuntime()
        if (intent?.action == ACTION_CONFIG_CHANGED) {
            restartCapabilityProbe()
        } else {
            ensureCapabilityProbe()
        }
        when (intent?.action) {
            ACTION_ACTIVATE_DIRECT -> {
                activateDirectPath()
                maybeStopSelf()
            }
            ACTION_DEACTIVATE_DIRECT -> {
                runtime?.deactivateSession()
                if (owner == ClipboardSyncOwner.Direct &&
                    runtime?.phase == ClipboardSyncRuntimePhase.INACTIVE
                ) {
                    owner = null
                }
                maybeStopSelf()
            }
            ACTION_CONFIG_CHANGED -> {
                runtime?.notifyConfigChanged()
                maybeStopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterScreenReceiver()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to unregister screen receiver", t)
        }
        try {
            unregisterImeChangeReceiver()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to unregister ime change receiver", t)
        }
        try {
            unregisterNetworkCallback()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to unregister network callback", t)
        }
        val existingRuntime = runtime
        runtime = null
        capabilityProbeJob?.cancel()
        try {
            existingRuntime?.forceDeactivateSession()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to deactivate on destroy", t)
        }
        if (runningInstance === this) {
            runningInstance = null
        }
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Runtime service destroyed")
    }

    private fun activateDirectPath() {
        if (owner != ClipboardSyncOwner.Direct || runtime == null) {
            tearDownRuntime()
            ensureRuntime()
        }
        owner = ClipboardSyncOwner.Direct
        runtime?.activateSession()
    }

    private fun tearDownRuntime() {
        val existing = runtime
        runtime = null
        if (existing != null) {
            try {
                existing.forceDeactivateSession()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop before session recreate", t)
            }
        }
    }

    private fun ensureRuntime() {
        if (runtime != null) return
        val createdSession = DirectClipboardSyncRuntimeSession(
            context = this,
            prefs = prefs,
            scope = serviceScope
        )
        lateinit var createdRuntime: ClipboardSyncRuntime
        createdRuntime = ClipboardSyncRuntime(
            syncEnabled = { prefs.syncClipboardEnabled },
            receiveMode = { prefs.syncClipboardReceiveMode },
            keepBackgroundRealtimeEnabled = { prefs.syncClipboardKeepBackgroundRealtimeEnabled },
            isScreenInteractive = { isScreenInteractive() },
            session = createdSession,
            scope = serviceScope,
            onTerminalPhase = {
                serviceScope.launch {
                    if (runtime === createdRuntime) {
                        maybeStopSelf()
                    }
                }
            }
        )
        runtime = createdRuntime
    }

    private fun restartCapabilityProbe() {
        capabilityProbeJob?.cancel()
        capabilityProbeJob = null
        ensureCapabilityProbe()
    }

    private fun ensureCapabilityProbe(delayMs: Long = 1_000L) {
        if (capabilityProbeJob?.isActive == true ||
            !prefs.syncClipboardEnabled ||
            prefs.syncClipboardReceiveMode == ClipboardSyncReceiveMode.OFF ||
            prefs.syncClipboardRealtimeSupported != null
        ) {
            return
        }
        capabilityProbeJob = serviceScope.launch {
            delay(delayMs)
            if (!prefs.syncClipboardEnabled ||
                prefs.syncClipboardReceiveMode == ClipboardSyncReceiveMode.OFF ||
                prefs.syncClipboardRealtimeSupported != null
            ) {
                capabilityProbeJob = null
                maybeStopSelf()
                return@launch
            }
            val server = prefs.syncClipboardServerBase
            val username = prefs.syncClipboardUsername
            val password = prefs.syncClipboardPassword
            val supported = withContext(Dispatchers.IO) {
                SyncClipboardSignalRClient.probeServerVersion(server, username, password)
            }
            if (supported != null &&
                prefs.syncClipboardRealtimeSupported == null &&
                server == prefs.syncClipboardServerBase &&
                username == prefs.syncClipboardUsername &&
                password == prefs.syncClipboardPassword
            ) {
                prefs.syncClipboardRealtimeSupported = supported
                prefs.syncClipboardReceiveMode = if (supported) {
                    ClipboardSyncReceiveMode.REALTIME
                } else {
                    ClipboardSyncReceiveMode.POLLING
                }
                runtime?.notifyConfigChanged()
            }
            capabilityProbeJob = null
            if (supported == null) {
                ensureCapabilityProbe(delayMs = 60_000L)
            }
            maybeStopSelf()
        }
    }

    private fun isScreenInteractive(): Boolean = try {
        val pm = getSystemService(PowerManager::class.java)
        pm?.isInteractive == true
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read screen interactive state", t)
        true
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }

    private fun registerImeChangeReceiver() {
        if (imeChangeReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            imeChangeReceiver,
            IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        imeChangeReceiverRegistered = true
    }

    private fun unregisterImeChangeReceiver() {
        if (!imeChangeReceiverRegistered) return
        unregisterReceiver(imeChangeReceiver)
        imeChangeReceiverRegistered = false
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        networkCallbackRegistered = false
    }

    private fun maybeStopSelf() {
        if (capabilityProbeJob?.isActive == true) return
        val phase = runtime?.phase
        if (phase == null || phase == ClipboardSyncRuntimePhase.INACTIVE) {
            stopSelf()
        }
        // SCREEN_OFF_DORMANT 及活动相保持 Service，供后台 realtime / 亮屏恢复。
    }
}
