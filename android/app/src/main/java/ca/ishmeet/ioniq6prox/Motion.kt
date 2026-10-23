package ca.ishmeet.ioniq6prox

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import ca.ishmeet.ioniq6prox.Ui.dpf

/** A spinning arc, drawn inside a button while its command runs. */
class RingView(c: Context, color: Int = Ui.TEXT) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = c.dpf(2.6f)
        this.color = color
    }
    private val track = Paint(paint).apply { this.color = Ui.alpha(color, 0.16f) }
    private var angle = 0f
    private var sweep = 90f
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val f = it.animatedValue as Float
            angle = f * 360f
            // the arc breathes between short and long, like most system spinners
            sweep = 60f + 200f * (0.5f - 0.5f * kotlin.math.cos(f * 2 * Math.PI).toFloat())
            invalidate()
        }
    }

    fun start() { visibility = VISIBLE; if (!spin.isRunning) spin.start() }
    fun stop() { spin.cancel(); visibility = GONE }

    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val inset = paint.strokeWidth
        val r = RectF(inset, inset, width - inset, height - inset)
        c.drawOval(r, track)
        c.drawArc(r, angle - 90f, sweep, false, paint)
    }
}

/**
 * Confirmation the way car apps do it: on success the button fills white with black content for
 * a moment, then eases back. On failure it tints coral and gives a small shake. Plus haptics.
 */
object Motion {
    /**
     * fill: the button's background shape. base: its normal colour.
     * paint(f, accent): recolour the button's content for blend amount f (0 = normal, 1 = full accent).
     */
    fun result(view: View, fill: GradientDrawable, base: Int, ok: Boolean, paint: (Float, Int) -> Unit) {
        val accent = if (ok) 0xFFFFFFFF.toInt() else Ui.alpha(Ui.CORAL, 0.28f)
        val ink = if (ok) Ui.BG else Ui.CORAL
        val argb = ArgbEvaluator()
        // quick in, hold, slow out
        val curve = PropertyValuesHolder.ofKeyframe("f",
            Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.12f, 1f), Keyframe.ofFloat(0.62f, 1f),
            Keyframe.ofFloat(1f, 0f).apply { interpolator = DecelerateInterpolator() })
        val anim = ValueAnimator.ofPropertyValuesHolder(curve).apply {
            duration = if (ok) 1_500 else 1_200
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.getAnimatedValue("f") as Float
                fill.setColor(argb.evaluate(f, base, accent) as Int)
                paint(f, ink)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { fill.setColor(base); paint(0f, ink) }
            })
        }
        anim.start()
        view.performHapticFeedback(if (ok) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
        if (!ok) ObjectAnimator.ofFloat(view, "translationX", 0f, -14f, 12f, -8f, 5f, 0f).setDuration(420).start()
    }

    fun blend(from: Int, to: Int, f: Float): Int = ArgbEvaluator().evaluate(f, from, to) as Int
}

/** Battery level as a bar, with a white knob at the charge limit (like the target-charge slider in car apps). */
class ChargeBarView(c: Context) : View(c) {
    var level: Int? = null; set(v) { field = v; invalidate() }
    var limit: Int? = null; set(v) { field = v; invalidate() }
    var charging = false; set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.RAISED }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT }
    private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(22f).toInt())

    override fun onDraw(c: Canvas) {
        val h = context.dpf(5f)
        val kr = context.dpf(9f)
        val left = kr
        val right = width - kr
        val cy = height / 2f
        c.drawRoundRect(RectF(left, cy - h / 2, right, cy + h / 2), h, h, track)
        level?.let { l ->
            fill.color = if (charging) Ui.GLACIER else Ui.alpha(Ui.TEXT, 0.82f)
            val x = left + (right - left) * l.coerceIn(0, 100) / 100f
            c.drawRoundRect(RectF(left, cy - h / 2, x, cy + h / 2), h, h, fill)
        }
        limit?.let { l ->
            val x = left + (right - left) * l.coerceIn(0, 100) / 100f
            c.drawCircle(x, cy, kr + context.dpf(2f), knobEdge)
            c.drawCircle(x, cy, kr, knob)
        }
    }
}
