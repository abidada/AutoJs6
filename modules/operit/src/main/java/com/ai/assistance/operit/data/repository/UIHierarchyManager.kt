package com.ai.assistance.operit.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import com.ai.assistance.operit.util.AppLogger
import android.widget.Toast
import com.ai.assistance.operit.R
import com.ai.assistance.operit.hostcompat.LocalAccessibilityProvider
import com.ai.assistance.operit.provider.IAccessibilityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI层次结构管理器
 *
 * Operit port (C16): the standalone accessibility provider app (remote APK + service
 * binding) was removed; the provider is now the in-process
 * [LocalAccessibilityProvider] which P4.1 maps onto the host AccessibilityService.
 * All public signatures are kept identical to upstream.
 */
object UIHierarchyManager {
    private const val TAG = "UIHierarchyManager"

    // In-process provider (host AccessibilityService backend in P4.1).
    private val accessibilityProvider: IAccessibilityProvider = LocalAccessibilityProvider()

    private val _isBound = MutableStateFlow(true)
    val isBound = _isBound.asStateFlow()

    private val bindingMutex = Mutex()

    /**
     * Operit port (C16): provider APK install flow removed (no standalone provider app).
     */
    fun launchProviderInstall(@Suppress("UNUSED_PARAMETER") context: Context) {
        AppLogger.w(TAG, "launchProviderInstall is a no-op: provider APK architecture removed (C16)")
    }

    /**
     * Operit port (C16): nothing to update — the provider lives in-process.
     */
    fun isUpdateNeeded(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return false
    }

    /**
     * 确保服务已绑定，如果未绑定则尝试自动重新绑定。
     * @return a boolean indicating if the service is ready.
     */
    private suspend fun ensureBound(context: Context): Boolean {
        // In-process provider is always available.
        return true
    }

    /**
     * Operit port (C16): there is no provider app anymore; kept for call-site compatibility.
     */
    fun isProviderAppInstalled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return true
    }

    /**
     * 绑定到外部无障碍服务。
     * 这是一个挂起函数，它会等待服务连接成功或失败。
     * @return a boolean indicating if the binding was successful.
     */
    suspend fun bindToService(context: Context): Boolean {
        return bindingMutex.withLock {
            AppLogger.d(TAG, "bindToService: in-process LocalAccessibilityProvider always bound (C16)")
            _isBound.value = true
            true
        }
    }

    /**
     * 解绑服务
     */
    fun unbindFromService(@Suppress("UNUSED_PARAMETER") context: Context) {
        // In-process provider: nothing to unbind.
    }

    /**
     * 从外部服务获取UI层次结构。
     * 如果服务未绑定，会尝试自动重新绑定一次。
     */
    suspend fun getUIHierarchy(context: Context): String {
        if (!ensureBound(context)) {
            AppLogger.e(TAG, "绑定失败，无法获取UI层次结构")
            return ""
        }
        return try {
            accessibilityProvider?.uiHierarchy ?: ""
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "从提供者获取UI层次结构失败", e)
            // Consider re-binding or notifying the user
            ""
        }
    }

    /**
     * 从UI层次结构的XML中解析出窗口信息（包名）。
     * 活动名称现在通过 getCurrentActivityName() 函数单独获取。
     * @param xmlHierarchy UI层次结构的XML字符串
     * @return 一个Pair，第一个元素是包名，第二个是null（活动名称需单独获取）。
     */
    fun extractWindowInfo(xmlHierarchy: String): Pair<String?, String?> {
        if (xmlHierarchy.isEmpty()) {
            return Pair(null, null)
        }
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlHierarchy))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            // 只获取根节点的包名，活动名称通过单独的函数获取
                            val rootPackage = parser.getAttributeValue(null, "package")
                            return Pair(rootPackage, null)
                        }
                    }
                }
                eventType = parser.next()
            }
            
            return Pair(null, null)

        } catch (e: Exception) {
            AppLogger.e(TAG, "解析窗口信息时出错", e)
            return Pair(null, null)
        }
    }

    /**
     * 请求远程服务在指定坐标执行点击。
     */
    suspend fun performClick(context: Context, x: Int, y: Int): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法执行点击")
            return false
        }
        return try {
            accessibilityProvider?.performClick(x, y) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求点击操作失败", e)
            false
        }
    }

    suspend fun performLongPress(context: Context, x: Int, y: Int): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法执行长按")
            return false
        }
        return try {
            accessibilityProvider?.performLongPress(x, y) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "长按点击操作失败", e)
            false
        }
    }

    /**
     * 请求远程服务执行滑动。
     */
    suspend fun performSwipe(context: Context, startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法执行滑动")
                return false
            }
        return try {
            accessibilityProvider?.performSwipe(startX, startY, endX, endY, duration) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求滑动操作失败", e)
            false
        }
    }

    /**
     * 请求远程服务执行全局操作。
     */
    suspend fun performGlobalAction(context: Context, actionId: Int): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法执行全局操作")
            return false
        }
        return try {
            accessibilityProvider?.performGlobalAction(actionId) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求全局操作失败", e)
            false
        }
    }

    /**
     * 请求远程服务查找有焦点的节点的ID。
     */
    suspend fun findFocusedNodeId(context: Context): String? {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法查找焦点节点")
            return null
        }
        return try {
            accessibilityProvider?.findFocusedNodeId()
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求查找焦点节点ID失败", e)
            null
        }
    }

    /**
     * 请求远程服务在指定ID的节点上设置文本。
     */
    suspend fun setTextOnNode(context: Context, nodeId: String, text: String): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法设置文本")
            return false
        }
        return try {
            accessibilityProvider?.setTextOnNode(nodeId, text) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求设置文本失败", e)
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "请求设置文本时远程服务发生异常", e)
            false
        }
    }

    /**
     * 请求远程服务截取屏幕截图。
     */
    suspend fun takeScreenshot(context: Context, path: String, format: String): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法截取屏幕截图")
            return false
        }
        return try {
            accessibilityProvider?.takeScreenshot(path, format) ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "请求截取屏幕截图失败", e)
            false
        }
    }

    /**
     * 检查远程无障碍服务是否已在系统设置中启用。
     */
    suspend fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法检查无障碍服务状态")
            return false
        }
        return try {
            accessibilityProvider?.isAccessibilityServiceEnabled ?: false
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }

    /**
     * 从远程服务获取当前Activity名称。
     */
    suspend fun getCurrentActivityName(context: Context): String? {
        if (!ensureBound(context)) {
            AppLogger.w(TAG, "绑定失败，无法获取Activity名称")
            return null
        }
        return try {
            accessibilityProvider?.currentActivityName
        } catch (e: RemoteException) {
            AppLogger.e(TAG, "从提供者获取Activity名称失败", e)
            null
        }
    }
}