package org.autojs.autojs.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.autojs.autojs.AutoJs
import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.mcp.tools.JobCancellation
import org.autojs.autojs.runtime.ScriptRuntime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared scope for MCP background work (delayed-cancel observers, etc.).
 */
internal val McpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Lazily creates and holds a [ScriptRuntime] dedicated to MCP tools, independent of
 * any running script — the counterpart of AutoX's `McpRuntimeProvider`.
 *
 * Requires [AutoJs.createMcpRuntime] exposed by the host (see AbstractAutoJs).
 */
class McpRuntimeProvider {
    private val lock = Any()
    private var runtime: ScriptRuntime? = null

    fun getRuntime(): ScriptRuntime {
        synchronized(lock) {
            val current = runtime
            if (current != null) {
                return current
            }
            val created = AutoJs.instance.createMcpRuntime()
            runtime = created
            return created
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { runtime?.onExit() }
            runtime = null
        }
    }
}

/**
 * Cancels exactly one MCP job by force-stopping the engine owned by [execution],
 * polling until the engine reports destroyed (mirrors AutoX PR#1 semantics).
 *
 * Deliberately bypasses `ScriptEngineService.stopAll()`-style paths that would kill
 * unrelated scripts.
 */
class ScriptExecutionCancellation(
    private val execution: ScriptExecution,
    private val onDelayedStop: () -> Unit
) : JobCancellation {
    private val stopRequested = AtomicBoolean(false)
    private val delayedObserverStarted = AtomicBoolean(false)

    override suspend fun cancelAndAwait(timeoutMillis: Long): Boolean {
        val stopped = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val engine = execution.engine
                if (engine != null) {
                    if (engine.isDestroyed) {
                        return@withTimeoutOrNull true
                    }
                    if (stopRequested.compareAndSet(false, true)) {
                        try {
                            engine.forceStop()
                        } catch (e: Throwable) {
                            stopRequested.set(false)
                            throw e
                        }
                    }
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!stopped) {
            stopRequested.set(false)
            observeDelayedStop()
        }
        return stopped
    }

    private fun observeDelayedStop() {
        if (!delayedObserverStarted.compareAndSet(false, true)) {
            return
        }
        McpScope.launch {
            val stopped = withTimeoutOrNull(DELAYED_STOP_OBSERVE_MILLIS) {
                while (execution.engine?.isDestroyed != true) {
                    delay(100)
                }
                true
            } ?: false
            if (stopped) {
                onDelayedStop()
            } else {
                delayedObserverStarted.set(false)
            }
        }
    }

    private companion object {
        const val DELAYED_STOP_OBSERVE_MILLIS = 60_000L
    }
}
