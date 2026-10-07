package org.autojs.autojs.mcp.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.stardust.autojs.core.util.ProcessShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.autojs.autojs.AutoJs
import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.accessibility.LayoutInspector
import org.autojs.autojs.core.accessibility.NodeInfo
import org.autojs.autojs.core.accessibility.UiSelector
import org.autojs.autojs.core.automator.GlobalActionAutomator
import org.autojs.autojs.core.image.ImageWrapper
import org.autojs.autojs.mcp.McpConfig
import org.autojs.autojs.mcp.McpResponse
import org.autojs.autojs.mcp.McpRuntimeProvider
import org.autojs.autojs.mcp.ScreenshotInfo
import org.autojs.autojs.mcp.ScreenshotStore
import org.autojs.autojs.mcp.tool.McpTool
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.ScriptPromiseAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

private val gson = Gson()

data class McpToolContext(
    val appContext: Context,
    val runtimeProvider: McpRuntimeProvider,
    val screenshotStore: ScreenshotStore,
    val configProvider: () -> McpConfig
)

data class TapRequest(val x: Int, val y: Int)
data class SwipeRequest(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val duration: Int? = 300)
data class FindElementRequest(
    val text: String? = null,
    val id: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val timeoutMillis: Long? = 2000
)
data class FindElementsRequest(
    val text: String? = null,
    val id: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val timeoutMillis: Long? = 2000,
    val limit: Int? = 20
)
data class ScreenshotRequest(val asBase64: Boolean? = false)
data class GetRecentScreenshotRequest(val asBase64: Boolean? = false)
data class OcrRequest(val source: String? = null, val path: String? = null, val language: String? = "zh")
data class AppControlRequest(val action: String, val packageName: String)

/**
 * Builds a standalone gesture automator that does not depend on a running script.
 * Callbacks are delivered on the main looper; the calling (Ktor IO) thread blocks
 * on the gesture result via VolatileDispose inside GlobalActionAutomator.
 */
internal fun createGlobalActionAutomator(context: Context): GlobalActionAutomator {
    return GlobalActionAutomator(context, Handler(Looper.getMainLooper())) {
        AccessibilityService.instance
            ?: throw IllegalStateException("accessibility service is not running")
    }
}

class TapTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, TapRequest::class.java)
            ?: return McpResponse.error("BadRequest", "x/y required")
        return try {
            val ok = createGlobalActionAutomator(ctx.appContext).click(req.x, req.y)
            if (ok) McpResponse.ok(mapOf("ok" to true)) else McpResponse.error("Failed", "tap failed")
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "tap failed")
        }
    }
}

class SwipeTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, SwipeRequest::class.java)
            ?: return McpResponse.error("BadRequest", "x1/y1/x2/y2 required")
        val duration = req.duration ?: 300
        return try {
            val ok = createGlobalActionAutomator(ctx.appContext)
                .swipe(req.x1, req.y1, req.x2, req.y2, duration.toLong())
            if (ok) McpResponse.ok(mapOf("ok" to true)) else McpResponse.error("Failed", "swipe failed")
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "swipe failed")
        }
    }
}

class FindElementTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, FindElementRequest::class.java) ?: FindElementRequest()
        if (req.text.isNullOrBlank() && req.id.isNullOrBlank() && req.desc.isNullOrBlank() && req.className.isNullOrBlank()) {
            return McpResponse.error("BadRequest", "criteria required")
        }
        ensureAccessibilityAvailable()
        // Build the selector on the shared runtime's bridge, mirroring ScriptRuntime.selector();
        // the no-arg constructor uses the static AccessibilityService.bridge, whose find() path
        // can return an empty collection when invoked from a service worker thread.
        val runtime = ctx.runtimeProvider.getRuntime()
        val selector = UiSelector(runtime.accessibilityBridge)
        applySelector(selector, req.text, req.id, req.desc, req.className)
        return try {
            val timeout = req.timeoutMillis ?: 2000
            val obj = selector.findOne(timeout)
            if (obj == null) {
                McpResponse.error("NotFound", "element not found")
            } else {
                val rect = obj.boundsInScreen()
                val data = mapOf(
                    "bounds" to mapOf("left" to rect.left, "top" to rect.top, "right" to rect.right, "bottom" to rect.bottom),
                    "center" to mapOf("x" to obj.center().x, "y" to obj.center().y),
                    "text" to obj.text(),
                    "id" to obj.id(),
                    "desc" to obj.desc(),
                    "className" to obj.className(),
                    "packageName" to obj.packageName()
                )
                McpResponse.ok(data)
            }
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "find_element failed")
        }
    }
}

class FindElementsTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, FindElementsRequest::class.java) ?: FindElementsRequest()
        if (req.text.isNullOrBlank() && req.id.isNullOrBlank() && req.desc.isNullOrBlank() && req.className.isNullOrBlank()) {
            return McpResponse.error("BadRequest", "criteria required")
        }
        ensureAccessibilityAvailable()
        // Same bridge choice as FindElementTool: see comment there.
        val runtime = ctx.runtimeProvider.getRuntime()
        val selector = UiSelector(runtime.accessibilityBridge)
        applySelector(selector, req.text, req.id, req.desc, req.className)
        return try {
            val timeout = req.timeoutMillis ?: 2000
            val limit = req.limit?.takeIf { it > 0 } ?: 20
            val collection = findCollectionWithTimeout(selector, timeout)
            if (collection.size() == 0) {
                return McpResponse.ok(mapOf("count" to 0, "elements" to emptyList<Map<String, Any>>()))
            }
            val results = ArrayList<Map<String, Any?>>(minOf(limit, collection.size()))
            for (i in 0 until collection.size()) {
                val obj = collection[i] ?: continue
                val rect = obj.boundsInScreen()
                results.add(
                    mapOf(
                        "bounds" to mapOf(
                            "left" to rect.left,
                            "top" to rect.top,
                            "right" to rect.right,
                            "bottom" to rect.bottom
                        ),
                        "center" to mapOf("x" to obj.center().x, "y" to obj.center().y),
                        "text" to obj.text(),
                        "id" to obj.id(),
                        "desc" to obj.desc(),
                        "className" to obj.className(),
                        "packageName" to obj.packageName()
                    )
                )
                if (results.size >= limit) {
                    break
                }
            }
            McpResponse.ok(mapOf("count" to results.size, "elements" to results))
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "find_elements failed")
        }
    }
}

class GetForegroundAppTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val runtime = ctx.runtimeProvider.getRuntime()
        val info = runtime.info
        val packageName = info.getLatestPackageByUsageStatsIfGranted()
            .takeIf { it.isNotBlank() }
            ?: info.latestPackage
        if (packageName.isBlank()) {
            return McpResponse.error("NotFound", "foreground app not found")
        }
        val pm = ctx.appContext.packageManager
        val label = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString() ?: packageName
        } catch (_: Exception) {
            packageName
        }
        val activity = info.latestActivity.takeIf { it.isNotBlank() }
        return McpResponse.ok(
            mapOf(
                "packageName" to packageName,
                "label" to label,
                "activity" to activity
            )
        )
    }
}

class GetCurrentActivityTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val runtime = ctx.runtimeProvider.getRuntime()
        val info = runtime.info
        val activity = info.latestActivity.takeIf { it.isNotBlank() }
            ?: return McpResponse.error("NotFound", "activity not found")
        val packageName = info.getLatestPackageByUsageStatsIfGranted()
            .takeIf { it.isNotBlank() }
            ?: info.latestPackage
        return McpResponse.ok(
            mapOf(
                "activity" to activity,
                "packageName" to packageName
            )
        )
    }
}

class ScreenshotTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, ScreenshotRequest::class.java) ?: ScreenshotRequest()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return McpResponse.error(
                "NotSupported",
                "screenshot requires Android 11+ (accessibility takeScreenshot API)"
            )
        }
        val runtime = ctx.runtimeProvider.getRuntime()
        var image: ImageWrapper? = null
        return try {
            image = captureImage(runtime)
            val file = saveImage(ctx.appContext, image)
            val info = ScreenshotInfo(
                path = file.absolutePath,
                width = image.width,
                height = image.height,
                timestamp = System.currentTimeMillis()
            )
            ctx.screenshotStore.update(info)
            val data = if (req.asBase64 == true) {
                if (!ctx.configProvider().allowBase64) {
                    return McpResponse.error("Forbidden", "base64 disabled by config")
                }
                mapOf(
                    "path" to info.path,
                    "width" to info.width,
                    "height" to info.height,
                    "timestamp" to info.timestamp,
                    "base64" to imageToBase64(image.bitmap)
                )
            } else {
                mapOf(
                    "path" to info.path,
                    "width" to info.width,
                    "height" to info.height,
                    "timestamp" to info.timestamp
                )
            }
            McpResponse.ok(data)
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "screenshot failed")
        } finally {
            runCatching { image?.shoot() }
        }
    }
}

class GetRecentScreenshotTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, GetRecentScreenshotRequest::class.java) ?: GetRecentScreenshotRequest()
        var info = ctx.screenshotStore.get()
        var captured: ImageWrapper? = null
        try {
            if (info == null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    return McpResponse.error(
                        "NotSupported",
                        "screenshot requires Android 11+ (accessibility takeScreenshot API)"
                    )
                }
                val runtime = ctx.runtimeProvider.getRuntime()
                val image = captureImage(runtime)
                val file = saveImage(ctx.appContext, image)
                info = ScreenshotInfo(
                    path = file.absolutePath,
                    width = image.width,
                    height = image.height,
                    timestamp = System.currentTimeMillis()
                )
                ctx.screenshotStore.update(info)
                captured = image
            }
            val data = mutableMapOf<String, Any>(
                "path" to info.path,
                "width" to info.width,
                "height" to info.height,
                "timestamp" to info.timestamp
            )
            if (req.asBase64 == true) {
                if (!ctx.configProvider().allowBase64) {
                    return McpResponse.error("Forbidden", "base64 disabled by config")
                }
                val base64 = if (captured != null) {
                    imageToBase64(captured.bitmap)
                } else {
                    val bitmap = BitmapFactory.decodeFile(info.path)
                        ?: return McpResponse.error("Failed", "image decode failed")
                    imageToBase64(bitmap)
                }
                data["base64"] = base64
            }
            return McpResponse.ok(data)
        } catch (e: Exception) {
            return McpResponse.error("Failed", e.message ?: "screenshot failed")
        } finally {
            runCatching { captured?.shoot() }
        }
    }
}

class OcrTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, OcrRequest::class.java) ?: OcrRequest()
        val runtime = ctx.runtimeProvider.getRuntime()
        var image: ImageWrapper? = null
        return try {
            image = when {
                req.path?.isNotBlank() == true -> {
                    val bitmap = BitmapFactory.decodeFile(req.path)
                        ?: return McpResponse.error("Failed", "image decode failed")
                    ImageWrapper.ofBitmap(runtime, bitmap)
                }
                req.source?.lowercase() == "screenshot" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        return McpResponse.error(
                            "NotSupported",
                            "screenshot source requires Android 11+; pass an image `path` instead"
                        )
                    }
                    val captured = captureImage(runtime)
                    val file = saveImage(ctx.appContext, captured)
                    val info = ScreenshotInfo(
                        path = file.absolutePath,
                        width = captured.width,
                        height = captured.height,
                        timestamp = System.currentTimeMillis()
                    )
                    ctx.screenshotStore.update(info)
                    captured
                }
                else -> null
            } ?: return McpResponse.error("BadRequest", "path or source required")

            // detect() consumes (shoots) the image internally.
            val results = runtime.ocrMLKit.detect(image)
            image = null
            McpResponse.ok(
                mapOf(
                    "count" to results.size,
                    "results" to results.map { r ->
                        mapOf(
                            "text" to r.text,
                            "confidence" to r.confidence,
                            "bounds" to listOf(r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom)
                        )
                    }
                )
            )
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "ocr failed")
        } finally {
            runCatching { image?.shoot() }
        }
    }
}

class AppControlTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        val req = parse(params, AppControlRequest::class.java)
            ?: return McpResponse.error("BadRequest", "action/packageName required")
        val runtime = ctx.runtimeProvider.getRuntime()
        return when (req.action.lowercase()) {
            "launch", "bring_to_front" -> {
                val ok = runtime.app.launchPackage(req.packageName)
                if (ok) McpResponse.ok(mapOf("ok" to true)) else McpResponse.error("Failed", "launch failed")
            }
            "force_stop" -> {
                // Root required; a plain shell `am force-stop` is denied on stock ROMs.
                val result = ProcessShell.exec("am force-stop ${req.packageName}", true)
                if (result.code == 0) McpResponse.ok(mapOf("ok" to true)) else McpResponse.error(
                    "Failed",
                    "force_stop failed (root required): ${result.error}"
                )
            }
            else -> McpResponse.error("BadRequest", "unsupported action")
        }
    }
}

class GetUiTreeTool(private val ctx: McpToolContext) : McpTool {
    override suspend fun handle(params: JsonObject?): McpResponse {
        return try {
            val inspector = AutoJs.instance.layoutInspector
            val capture = captureLayout(inspector) ?: return McpResponse.error("Failed", "capture failed")
            val data = buildCompactNodes(capture.root, null, true).firstOrNull()
                ?: return McpResponse.error("Failed", "capture failed")
            McpResponse.ok(data)
        } catch (e: Exception) {
            McpResponse.error("Failed", e.message ?: "capture failed")
        }
    }
}

private fun ensureAccessibilityAvailable() {
    if (AccessibilityService.instance == null) {
        throw IllegalStateException("accessibility service is not running")
    }
}

private suspend fun captureLayout(inspector: LayoutInspector): org.autojs.autojs.core.accessibility.Capture? {
    val deferred = CompletableDeferred<org.autojs.autojs.core.accessibility.Capture?>()
    val listener = object : LayoutInspector.CaptureAvailableListener {
        override fun onCaptureAvailable(capture: org.autojs.autojs.core.accessibility.Capture, context: Context) {
            inspector.removeCaptureAvailableListener(this)
            deferred.complete(capture)
        }
    }
    inspector.addCaptureAvailableListener(listener)
    if (!inspector.captureCurrentWindow()) {
        inspector.removeCaptureAvailableListener(listener)
        return inspector.capture
    }
    return withTimeoutOrNull(TimeUnit.SECONDS.toMillis(3)) { deferred.await() } ?: inspector.capture
}

private fun buildCompactNodes(node: NodeInfo, parentPackage: String?, isRoot: Boolean): List<Map<String, Any?>> {
    val bounds = node.boundsInScreen
    val data = LinkedHashMap<String, Any?>()

    val hasSignal = hasNodeSignal(node)

    val className = node.className
    if (!className.isNullOrBlank()) {
        data["c"] = className
    }

    if (hasSignal || isRoot) {
        node.id?.takeIf { it.isNotBlank() }?.let { data["id"] = it }
        node.text.takeIf { it.isNotBlank() }?.let { data["t"] = it }
        node.desc?.takeIf { it.isNotBlank() }?.let { data["d"] = it }
    }

    val pkg = node.packageName?.takeIf { it.isNotBlank() }
    if (pkg != null && pkg != parentPackage) {
        data["p"] = pkg
    }

    data["b"] = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)

    val flags = StringBuilder()
    if (node.clickable) flags.append('c')
    if (node.focusable) flags.append('f')
    if (node.scrollable) flags.append('s')
    if (node.longClickable) flags.append('l')
    if (!node.enabled) flags.append('d')
    if (flags.isNotEmpty() && (hasSignal || isRoot)) {
        data["a"] = flags.toString()
    }

    val nextParent = pkg ?: parentPackage
    val children = node.children
    val hasDirectSignalChild = children.any { hasNodeSignal(it) }
    val keptChildren = children.flatMap { buildCompactNodes(it, nextParent, false) }

    val keepNode = isRoot || hasSignal || hasDirectSignalChild
    if (keepNode) {
        if (keptChildren.isNotEmpty()) {
            data["children"] = keptChildren
        }
        return listOf(data)
    }
    return keptChildren
}

private fun hasNodeSignal(node: NodeInfo): Boolean {
    if (node.clickable || node.focusable || node.scrollable || node.longClickable) {
        return true
    }
    if (node.text.isNotBlank()) {
        return true
    }
    if (!node.desc.isNullOrBlank()) {
        return true
    }
    if (!node.id.isNullOrBlank()) {
        return true
    }
    return false
}

private fun applySelector(
    selector: UiSelector,
    text: String?,
    id: String?,
    desc: String?,
    className: String?
) {
    text?.takeIf { it.isNotBlank() }?.let { selector.text(it) }
    id?.takeIf { it.isNotBlank() }?.let { selector.id(it) }
    desc?.takeIf { it.isNotBlank() }?.let { selector.desc(it) }
    className?.takeIf { it.isNotBlank() }?.let { selector.className(it) }
}

private fun findCollectionWithTimeout(selector: UiSelector, timeoutMillis: Long) =
    if (timeoutMillis <= 0) {
        selector.find()
    } else {
        val start = android.os.SystemClock.uptimeMillis()
        var collection = selector.find()
        while (collection.size() == 0 && android.os.SystemClock.uptimeMillis() - start < timeoutMillis) {
            Thread.sleep(50)
            collection = selector.find()
        }
        collection
    }

private fun <T> parse(params: JsonObject?, clazz: Class<T>): T? {
    return try {
        if (params == null) null else gson.fromJson(params, clazz)
    } catch (_: Exception) {
        null
    }
}

/** Android R+ only: accessibility takeScreenshot, no MediaProjection permission needed. */
@RequiresApi(Build.VERSION_CODES.R)
private suspend fun captureImage(runtime: ScriptRuntime): ImageWrapper {
    val promise = runtime.automator.captureScreen()
    val deferred = CompletableDeferred<ImageWrapper?>()
    promise.onResolve(object : ScriptPromiseAdapter.Callback {
        override fun call(arg: Any?) {
            deferred.complete(arg as? ImageWrapper)
        }
    })
    return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MILLIS) { deferred.await() }
        ?: throw IllegalStateException("screenshot failed or timed out")
}

private suspend fun saveImage(context: Context, image: ImageWrapper): File = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "mcp-screenshot-${System.currentTimeMillis()}.png")
    // ImageWrapper.saveTo resolves its path via scriptRuntime.files.cwd(), which requires a
    // script engine attached to the runtime; the shared MCP runtime has none, so save the
    // bitmap directly instead.
    file.outputStream().use { image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    file
}

private fun imageToBase64(source: Bitmap): String {
    val output = ByteArrayOutputStream()
    val scaled = scaleBitmapIfNeeded(source, BASE64_MAX_DIMENSION)
    val target = scaled ?: source
    target.compress(Bitmap.CompressFormat.JPEG, BASE64_JPEG_QUALITY, output)
    scaled?.recycle()
    return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
}

private fun scaleBitmapIfNeeded(source: Bitmap, maxDimension: Int): Bitmap? {
    val width = source.width
    val height = source.height
    val maxSide = maxOf(width, height)
    if (maxSide <= maxDimension) {
        return null
    }
    val scale = maxDimension.toFloat() / maxSide.toFloat()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
}

private const val SCREENSHOT_TIMEOUT_MILLIS = 5_000L
private const val BASE64_MAX_DIMENSION = 720
private const val BASE64_JPEG_QUALITY = 70
