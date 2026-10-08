package com.ai.assistance.operit.hostcompat

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.os.Bundle
import android.util.Xml
import com.ai.assistance.operit.provider.IAccessibilityProvider
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process [IAccessibilityProvider] implementation (C16: only the host accessibility
 * stack survives; the standalone provider-APK architecture was removed).
 *
 * P4.1: the AIDL surface maps onto [HostAccessibilityBackend] primitives injected by the
 * host at [OperitLibrary.init]:
 *  - [getUiHierarchy]        → backend root snapshot, serialized as a uiautomator-style
 *                              `<node>` tree (the schema the upstream consumers parse:
 *                              package/class/text/content-desc/resource-id/bounds/clickable…)
 *  - [findFocusedNodeId] /   → closed-loop node cache: the focus node is stored under a
 *    [setTextOnNode]           sequence id and consumed by the very next setText call
 *                              (upstream consumers never parse ids out of the XML)
 *  - click/longPress/swipe/  → backend gestures (host GlobalActionAutomator)
 *    globalAction/screenshot
 *
 * Thread-safety: every AIDL method may be invoked from binder workers concurrently;
 * node caching is synchronized and the XML serializer is per-invocation.
 *
 * Upstream sync rule: the AIDL file stays 1:1 with upstream; this impl is host-only.
 */
class LocalAccessibilityProvider : IAccessibilityProvider.Stub() {

    private val backend: HostAccessibilityBackend?
        get() = OperitLibrary.accessibilityBackend

    // ---- Focused-node cache (findFocusedNodeId → setTextOnNode closed loop) ----

    private val cacheLock = Any()
    private val nodeCache = LinkedHashMap<String, AccessibilityNodeInfo>(16, 0.75f, true)
    private val cacheSeq = AtomicLong(0)

    companion object {
        private const val TAG = "LocalAccessibilityProvider"

        /** Hard caps so a pathological window can never wedge the binder worker. */
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 60

        /** Node cache bound; over this, eldest entries drop off (access-order map). */
        private const val MAX_CACHED_NODES = 64
    }

    // =====================================================================
    // UI hierarchy
    // =====================================================================

    override fun getUiHierarchy(): String {
        val root = backend?.getRootNode() ?: return ""
        return try {
            serializeHierarchy(root)
        } catch (e: Exception) {
            com.ai.assistance.operit.util.AppLogger.e(TAG, "serialize ui hierarchy failed", e)
            ""
        }
    }

    /**
     * uiautomator-style `<node>` XML. The host serializer escapes newlines as `&#10;`,
     * matching the upstream consumer's `replace("&#10;", "\n")` un-escape step; bounds
     * use the `[l,t][r,b]` format parsed by upstream [parseBounds] equivalents.
     */
    private fun serializeHierarchy(root: AccessibilityNodeInfo): String {
        val writer = StringWriter(16 * 1024)
        val serializer = Xml.newSerializer()
        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "hierarchy")
        serializeNode(serializer, root, 0, IntArray(1))
        serializer.endTag(null, "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }

    private fun serializeNode(
        serializer: org.xmlpull.v1.XmlSerializer,
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
    ) {
        if (depth > MAX_DEPTH || counter[0] > MAX_NODES) return
        counter[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)

        serializer.startTag(null, "node")
        serializer.attribute(null, "index", depth.toString())
        serializer.attribute(null, "text", node.text?.toString() ?: "")
        serializer.attribute(null, "resource-id", node.viewIdResourceName ?: "")
        serializer.attribute(null, "class", node.className?.toString() ?: "")
        serializer.attribute(null, "package", node.packageName?.toString() ?: "")
        serializer.attribute(null, "content-desc", node.contentDescription?.toString() ?: "")
        serializer.attribute(null, "checkable", node.isCheckable.toString())
        serializer.attribute(null, "checked", node.isChecked.toString())
        serializer.attribute(null, "clickable", node.isClickable.toString())
        serializer.attribute(null, "enabled", node.isEnabled.toString())
        serializer.attribute(null, "focusable", node.isFocusable.toString())
        serializer.attribute(null, "focused", node.isFocused.toString())
        serializer.attribute(null, "scrollable", node.isScrollable.toString())
        serializer.attribute(null, "long-clickable", node.isLongClickable.toString())
        serializer.attribute(null, "password", node.isPassword.toString())
        serializer.attribute(null, "selected", node.isSelected.toString())
        serializer.attribute(
            null, "bounds",
            "[%d,%d][%d,%d]".format(rect.left, rect.top, rect.right, rect.bottom)
        )

        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (!child.isVisibleToUser && child.childCount == 0) {
                // Keep hidden leaves out of AI context (upstream dumps visible trees);
                // retain hidden containers so visible descendants survive.
                continue
            }
            serializeNode(serializer, child, depth + 1, counter)
        }

        serializer.endTag(null, "node")
    }

    // =====================================================================
    // Gestures & actions
    // =====================================================================

    override fun performClick(x: Int, y: Int): Boolean =
        backend?.tap(x, y) ?: false

    override fun performLongPress(x: Int, y: Int): Boolean =
        backend?.longPress(x, y) ?: false

    override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
        backend?.swipe(startX, startY, endX, endY, duration) ?: false

    override fun performGlobalAction(actionId: Int): Boolean =
        backend?.performGlobalAction(actionId) ?: false

    override fun takeScreenshot(path: String, format: String): Boolean =
        backend?.takeScreenshotTo(path, format) ?: false

    // =====================================================================
    // Focused node → setText closed loop
    // =====================================================================

    override fun findFocusedNodeId(): String? {
        val root = backend?.getRootNode() ?: return null
        val focused = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }.getOrNull() ?: return null

        val id = "n${cacheSeq.incrementAndGet()}"
        synchronized(cacheLock) {
            nodeCache[id] = focused
            while (nodeCache.size > MAX_CACHED_NODES) {
                val eldest = nodeCache.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return id
    }

    override fun setTextOnNode(nodeId: String, text: String): Boolean {
        val node = synchronized(cacheLock) { nodeCache.remove(nodeId) } ?: return false
        return runCatching {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    // =====================================================================
    // Status
    // =====================================================================

    override fun isAccessibilityServiceEnabled(): Boolean =
        backend?.isServiceRunning() ?: false

    override fun getCurrentActivityName(): String? =
        backend?.currentActivityName()
}
