/**
 * 监听面板：LISTENING 态底部停靠 overlay（设计参考 dicio 图4 交互，代码为全新实现）。
 *
 * 数据流：订阅 AsrResultBroadcaster（Partial/Final/Error 实时回显）；
 * 生命周期由 FloatingAsrInteractionController 在进入/退出 LISTENING 时调用 show/hide。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private var stopButton: TextView? = null
    private var lastFinalText: String? = null

    /** 停止按钮回调（由 FloatingAsrService 注入，等价单击「正在听...」胶囊）。 */
    var onStopClicked: (() -> Unit)? = null

    private val resultListener = object : AsrResultBroadcaster.Listener {
        override fun onPartial(text: String) {
            post { applyText(if (text.isBlank()) null else text) }
        }

        override fun onFinal(text: String) {
            lastFinalText = text
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
                val view = buildPanel()
                textLabel = view.findViewById(R.id.listeningPanelText)
                stopButton = view.findViewById(R.id.listeningPanelStop)
                windowManager.addView(view, buildLayoutParams())
                panelView = view
                lastFinalText = null
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
            stopButton = null
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
        val density = overlayContext.resources.displayMetrics.density

        fun dp(v: Float): Int = (v * density + 0.5f).toInt()
        fun dp(v: Int): Int = dp(v.toFloat())

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

        // 顶部拖动把手条（纯视觉）
        root.addView(
            View(overlayContext).apply {
                background = GradientDrawable().apply {
                    setColor(HANDLE_BAR_COLOR)
                    cornerRadius = dp(2f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        )

        // 中央大字文本
        root.addView(
            TextView(overlayContext).apply {
                id = R.id.listeningPanelText
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 6
                setLineSpacing(dp(2f).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14); bottomMargin = dp(14) }
            }
        )

        // 底部按钮行：复制 / 停止 / 分享
        val row = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(
            Button(overlayContext).apply {
                id = R.id.listeningPanelCopy
                text = context.getString(R.string.listening_panel_copy)
                setTextColor(0xCCFFFFFF.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { copyCurrentText() }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )

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
                ).apply { marginStart = dp(8); marginEnd = dp(8) }
            }
        )

        row.addView(
            Button(overlayContext).apply {
                id = R.id.listeningPanelShare
                text = context.getString(R.string.listening_panel_share)
                setTextColor(0xCCFFFFFF.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { shareCurrentText() }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
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
    }

    private fun copyCurrentText() {
        val text = textLabel?.text?.toString().orEmpty()
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bibi_asr", text))
            Toast.makeText(appContext, R.string.listening_panel_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to copy panel text", e)
        }
    }

    private fun shareCurrentText() {
        val text = textLabel?.text?.toString().orEmpty()
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(intent, null))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to share panel text", e)
        }
    }
}
