/**
 * 监听面板：LISTENING 态底部停靠 overlay（设计参考 dicio 图4 交互，代码为全新实现）。
 *
 * 数据流：订阅 AsrResultBroadcaster（Partial/Final/Error 实时回显）；
 * 生命周期由 FloatingAsrInteractionController 在进入/退出 LISTENING 时调用 show/hide。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.AsrResultBroadcaster
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes

internal class ListeningPanelHelper(
    private val appContext: Context,
    private val overlayContext: Context,
    private val windowManager: WindowManager,
    private val prefs: Prefs
) {
    companion object {
        private const val TAG = "ListeningPanel"
        private const val PANEL_BG_DARK = 0xF0161618.toInt()
        private const val HANDLE_BAR_COLOR = 0x55FFFFFF
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelView: View? = null
    private var textLabel: TextView? = null
    private var textScroll: FollowScrollView? = null

    /** 自动跟底：用户上滑回看历史时暂停，滚回底部后恢复。 */
    private var autoFollow = true

    /** 文本区是否展开到更高的档位（点击顶部把手切换）。 */
    private var expanded = false

    /** 停止按钮回调（由 FloatingAsrService 注入，等价单击「正在听...」胶囊）。 */
    var onStopClicked: (() -> Unit)? = null

    private val resultListener = object : AsrResultBroadcaster.Listener {
        override fun onPartial(text: String) {
            post { applyText(if (text.isBlank()) null else text) }
        }

        override fun onFinal(text: String) {
            post { applyText(text.ifBlank { null }) }
        }

        override fun onError(msg: String) {
            post { applyText(appContext.getString(R.string.listening_panel_error_prefix, msg)) }
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ==================== 生命周期 ====================

    fun show() {
        post {
            if (panelView != null) return@post
            try {
                expanded = false
                autoFollow = true
                val view = buildPanel()
                textLabel = view.findViewById(R.id.listeningPanelText)
                windowManager.addView(view, buildLayoutParams())
                panelView = view
                applyText(null)
                AsrResultBroadcaster.add(resultListener)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to show listening panel", e)
                panelView = null
            }
        }
    }

    fun hide() {
        post {
            AsrResultBroadcaster.remove(resultListener)
            val view = panelView ?: return@post
            panelView = null
            textLabel = null
            textScroll = null
            try {
                windowManager.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to hide listening panel", e)
            }
        }
    }

    val isShowing: Boolean
        get() = panelView != null

    /** 分发反馈（S5）：在面板上展示命中/未命中结果。 */
    fun showFeedback(message: String) {
        post { applyText(message) }
    }

    // ==================== 构建 ====================

    private fun dp(v: Float): Int = (v * overlayContext.resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    /** 文本区高度上限：预留按钮行/边距/导航栏避让，保证面板整体不超出屏幕。 */
    private fun maxTextHeightPx(): Int {
        val screenH = overlayContext.resources.displayMetrics.heightPixels
        val usable = (screenH - dp(160)).coerceAtLeast(dp(120))
        return if (expanded) usable else maxOf((usable * 0.55f).toInt(), dp(220))
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
    }

    private fun buildPanel(): View {
        val theme = BibiViewThemes.resolve(overlayContext, prefs)

        val root = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(PANEL_BG_DARK)
                cornerRadii = floatArrayOf(
                    dp(20f).toFloat(), dp(20f).toFloat(),
                    dp(20f).toFloat(), dp(20f).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        // 手势导航栏避让：窗口已被系统 fit 时回调为 0（不加双份留白），仅在面板真实压到导航栏时补偿
        val baseBottomPadding = dp(16)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (bottom > 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottomPadding + bottom)
            }
            insets
        }

        // 顶部把手（视觉 4dp 条 + 20dp 触达区）：点击在限高/展开两档间切换，方便听写后通读全文
        root.addView(
            View(overlayContext).apply {
                background = InsetDrawable(
                    GradientDrawable().apply {
                        setColor(HANDLE_BAR_COLOR)
                        cornerRadius = dp(2f).toFloat()
                    },
                    0, dp(8), 0, dp(8)
                )
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(20)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener {
                    expanded = !expanded
                    textScroll?.let { s ->
                        s.maxHeightPx = maxTextHeightPx()
                        s.requestLayout()
                        if (autoFollow) s.post { s.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        )

        // 识别文本区：限高 + 内部滚动，partial 流式刷新时自动跟底显示最新文字
        val scroll = FollowScrollView(overlayContext, PANEL_BG_DARK).apply {
            maxHeightPx = maxTextHeightPx()
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            atBottomListener = { atBottom -> autoFollow = atBottom }
        }
        scroll.addView(
            TextView(overlayContext).apply {
                id = R.id.listeningPanelText
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(2f).toFloat(), 1f)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(14) }
        )
        textScroll = scroll

        // 底部按钮行：停止（绿色「正在听」胶囊，居中）
        val row = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        row.addView(
            TextView(overlayContext).apply {
                id = R.id.listeningPanelStop
                text = context.getString(R.string.listening_panel_listening)
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(theme.success)
                    cornerRadius = dp(24f).toFloat()
                }
                setPadding(dp(20), dp(10), dp(20), dp(10))
                setOnClickListener {
                    onStopClicked?.invoke()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    // ==================== 行为 ====================

    private fun applyText(text: String?) {
        val label = textLabel ?: return
        label.text = text ?: appContext.getString(R.string.listening_panel_placeholder)
        label.alpha = if (text == null) 0.55f else 1f
        if (autoFollow) {
            val scroll = textScroll ?: return
            // post 到布局完成后滚动，否则刚 setText 的内容高度还未生效
            scroll.post {
                if (autoFollow) scroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /**
     * 限高滚动容器：
     * - onMeasure 先按内容自然测量，再钳制到 [maxHeightPx]，面板整体不超出屏幕；
     * - onScrollChanged 判断是否已到底部，驱动自动跟底的暂停/恢复；
     * - getSolidColor 用面板底色，渐隐边与背景融合。
     */
    private class FollowScrollView(
        context: Context,
        private val fadeColor: Int
    ) : ScrollView(context) {

        var maxHeightPx: Int = Int.MAX_VALUE
        var atBottomListener: ((Boolean) -> Unit)? = null

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            atBottomListener?.invoke(!canScrollVertically(1))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (measuredHeight > maxHeightPx) {
                setMeasuredDimension(measuredWidth, maxHeightPx)
            }
        }

        override fun getSolidColor(): Int = fadeColor
    }
}
