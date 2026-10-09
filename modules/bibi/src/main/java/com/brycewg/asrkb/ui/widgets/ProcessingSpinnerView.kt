package com.brycewg.asrkb.ui.widgets

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ProcessingSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        strokeWidth = 6f
    }

    private var sweepAngle: Float = 90f
    private var secondarySweepAngle: Float = 40f
    private var secondaryArcAlpha: Float = 0.4f
    private var secondaryArcEnabled: Boolean = false
    private var animator: ObjectAnimator? = null
    private var shouldAnimate: Boolean = false

    fun setSpinnerColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setStrokeWidth(widthPx: Float) {
        paint.strokeWidth = widthPx
        invalidate()
    }

    fun setSweepAngle(deg: Float) {
        sweepAngle = deg
        invalidate()
    }

    fun setSecondaryArcEnabled(enabled: Boolean) {
        secondaryArcEnabled = enabled
        invalidate()
    }

    fun setSecondarySweepAngle(deg: Float) {
        secondarySweepAngle = deg
        invalidate()
    }

    fun setSecondaryArcAlpha(alpha: Float) {
        secondaryArcAlpha = alpha.coerceIn(0f, 1f)
        invalidate()
    }

    fun start() {
        shouldAnimate = true
        startAnimationIfPossible()
    }

    fun stop() {
        shouldAnimate = false
        animator?.cancel()
        animator = null
        rotation = 0f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this && visibility == View.VISIBLE && shouldAnimate) {
            // 当视图变为可见且应该动画时，确保动画正在运行
            startAnimationIfPossible()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 当视图附加到窗口时，如果应该动画则启动
        if (shouldAnimate) {
            post { startAnimationIfPossible() }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun startAnimationIfPossible() {
        // 只有当视图可见、已附加到窗口且应该动画时才启动
        if (!shouldAnimate || visibility != View.VISIBLE || !isAttachedToWindow) {
            return
        }

        // 如果动画已经在运行，不需要重新创建
        if (animator?.isRunning == true) {
            return
        }

        // 取消旧动画（如果存在）
        animator?.cancel()

        // 旋转 View.ROTATION 属性: RenderNode transform 由 RenderThread 合成,
        // 每帧不触发 onDraw/display list 重录 (旧实现每帧 invalidate 全窗口重绘).
        animator = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // 更贴近边缘：减少内缩量
        val radius = (minOf(w, h) / 2f) - paint.strokeWidth * 0.5f
        val left = cx - radius
        val top = cy - radius
        val right = cx + radius
        val bottom = cy + radius
        // 静态绘制弧线; 旋转由 View.ROTATION 属性动画在 RenderThread 合成, 不在此 canvas.rotate
        canvas.drawArc(left, top, right, bottom, -90f, sweepAngle, false, paint)
        if (secondaryArcEnabled) {
            val originalColor = paint.color
            paint.color = applyAlpha(originalColor, secondaryArcAlpha)
            canvas.drawArc(left, top, right, bottom, 90f, secondarySweepAngle, false, paint)
            paint.color = originalColor
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
