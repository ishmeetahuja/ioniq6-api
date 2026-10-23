package ca.ishmeet.ioniq6prox

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import ca.ishmeet.ioniq6prox.Ui.dpf

/**
 * Round speedometer: a 240° arc that fills with the speed, the number in the middle. Grey "0" when
 * there's no speed (only shown while connected to the car's Bluetooth). The needle eases between readings.
 */
class SpeedGaugeView(c: Context) : View(c) {
    var kmh: Float? = null
        set(v) {
            if (field == v) return
            field = v
            val to = (v ?: 0f).coerceIn(0f, MAX)
            anim?.cancel()
            anim = ValueAnimator.ofFloat(shown, to).apply {
                duration = 700
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var shown = 0f
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = c.dpf(8f); color = Ui.RAISED
    }
    private val arc = Paint(track).apply { color = Ui.GLACIER }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.display; textAlign = Paint.Align.CENTER; textSize = c.dpf(38f)
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.body; textAlign = Paint.Align.CENTER; textSize = c.dpf(12f); color = Ui.MUTED
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpf(104f).toInt())

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val r = minOf(width / 2f, height / 1.5f) - track.strokeWidth
        val cx = width / 2f
        val cy = track.strokeWidth + r
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 150f, 240f, false, track)
        val live = kmh != null
        if (shown > 0.5f) c.drawArc(box, 150f, 240f * shown / MAX, false, arc)
        num.color = if (live) Ui.TEXT else Ui.MUTED
        c.drawText(shown.toInt().toString(), cx, cy + num.textSize * 0.32f, num)
        c.drawText("km/h", cx, cy + num.textSize * 0.32f + unit.textSize * 1.6f, unit)
    }

    companion object { const val MAX = 160f }
}

/** A navigation arrow that points at the parked car (rotated by the screen as the phone turns). */
class ArrowView(c: Context) : View(c) {
    var degrees: Float? = null
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.RAISED }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GLACIER }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, minOf(cx, cy), bg)
        val d = degrees
        if (d == null) { c.drawCircle(cx, cy, context.dpf(3f), dot); return }
        val s = minOf(width, height) / 34f
        c.save()
        c.rotate(d, cx, cy)
        c.translate(cx - 12f * s, cy - 12f * s)
        c.scale(s, s)
        c.drawPath(Path().apply { moveTo(12f, 3f); lineTo(19f, 21f); lineTo(12f, 17f); lineTo(5f, 21f); close() }, fill)
        c.restore()
    }
}

/**
 * Press-and-hold ring for Unlock: fills around the button over [holdMs], then calls [onFull].
 * Let go early and it empties again; nothing is sent.
 */
class HoldRingView(c: Context, color: Int, private val holdMs: Long, private val onFull: () -> Unit) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = c.dpf(3f); this.color = color
    }
    private var f = 0f
    private var anim: ValueAnimator? = null

    fun press() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(f, 1f).apply {
            duration = (holdMs * (1f - f)).toLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener { f = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    f = 0f; invalidate()
                    onFull()
                }
            })
            start()
        }
    }

    /** Let go: true if it hadn't filled yet (so nothing happens). */
    fun release(): Boolean {
        val wasHolding = anim?.isRunning == true
        anim?.cancel()
        if (f > 0f) {
            anim = ValueAnimator.ofFloat(f, 0f).apply {
                duration = 180
                addUpdateListener { f = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        return wasHolding
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (f <= 0f) return
        val i = paint.strokeWidth
        c.drawArc(RectF(i, i, width - i, height - i), -90f, 360f * f, false, paint)
    }
}
