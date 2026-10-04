/**
 * 悬浮球 View 树工厂，替代旧 floating_asr_ball.xml 布局。
 *
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes

internal object FloatingBallComposeViewFactory {

    fun create(context: Context, prefs: Prefs): View = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val ballSizePx = dp(context, readBallSizeDp(prefs))
        addView(createBallContainer(context, ballSizePx))
        addView(createPillContainer(context, ballSizePx))
        applyTheme(this, prefs)
    }

    fun applyTheme(root: View, prefs: Prefs) {
        val context = root.context
        val theme = BibiViewThemes.resolve(context)
        root.findViewById<ImageView>(R.id.edgeHandleIcon)?.imageTintList =
            ColorStateList.valueOf(theme.floatingIcon)
        root.findViewById<ImageView>(R.id.ballIcon)?.imageTintList =
            ColorStateList.valueOf(theme.floatingIcon)
        root.findViewById<View>(R.id.pillContainer)?.apply {
            background = roundedPillBackground(
                color = theme.primary,
                radiusPx = dp(context, readBallSizeDp(prefs)) / 2f
            )
        }
        root.findViewById<ImageView>(R.id.pillIcon)?.imageTintList =
            ColorStateList.valueOf(Color.WHITE)
        root.findViewById<TextView>(R.id.pillText)?.setTextColor(Color.WHITE)
    }

    /** 胶囊容器：麦克风小图标 + 文本；默认 GONE，由 ViewManager 按交互模式切换。 */
    private fun createPillContainer(context: Context, ballSizePx: Int): View =
        LinearLayout(context).apply {
            id = R.id.pillContainer
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val hPad = dp(context, 14)
            setPadding(hPad, 0, hPad, 0)
            addView(
                ImageView(context).apply {
                    id = R.id.pillIcon
                    setImageResource(R.drawable.microphone_floatingball)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(context, 8)
                    }
                }
            )
            addView(
                TextView(context).apply {
                    id = R.id.pillText
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    textSize = 15f
                    setSingleLine(true)
                    // 纯状态字（无跑马灯）：超宽兜底尾部省略
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER_VERTICAL }
                }
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                ballSizePx,
                Gravity.CENTER
            )
        }

    private fun roundedPillBackground(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
        }

    private fun createBallContainer(context: Context, ballSizePx: Int): View = FrameLayout(context).apply {
        id = R.id.ballContainer
        layoutParams = FrameLayout.LayoutParams(
            ballSizePx,
            ballSizePx,
            Gravity.CENTER
        )
        addView(createEdgeHandleIcon(context))
        addView(createBallIcon(context))
    }

    private fun createEdgeHandleIcon(context: Context): View = ImageView(context).apply {
        id = R.id.edgeHandleIcon
        alpha = 0f
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.cd_floating_edge_handle)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.angle_bracket_right)
        layoutParams = FrameLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
    }

    private fun createBallIcon(context: Context): View = ImageView(context).apply {
        id = R.id.ballIcon
        contentDescription = context.getString(R.string.cd_floating_asr)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.microphone_floatingball)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun readBallSizeDp(prefs: Prefs): Int = try {
        prefs.floatingBallSizeDp
    } catch (_: Throwable) {
        56
    }
}
