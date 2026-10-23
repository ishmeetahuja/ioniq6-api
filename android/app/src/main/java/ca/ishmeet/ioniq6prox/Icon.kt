package ca.ishmeet.ioniq6prox

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Line icons drawn on a 24×24 grid with round caps — no icon library needed. */
class Icon(private val kind: Kind, color: Int, private val sizePx: Int, knob: Int = Ui.BG) : Drawable() {

    enum class Kind { LOCK, UNLOCK, CLIMATE, CHARGE, FIND, SETTINGS, REFRESH, BACK, PAUSE, PLAY }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun setColor(c: Int) { stroke.color = c; fill.color = c; invalidateSelf() }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        when (kind) {
            Kind.LOCK -> {
                c.drawRoundRect(RectF(5f, 10.5f, 19f, 20.5f), 2.5f, 2.5f, stroke)
                c.drawPath(Path().apply { moveTo(8f, 10.5f); lineTo(8f, 7.5f); arcTo(RectF(8f, 3.5f, 16f, 11.5f), 180f, 180f); lineTo(16f, 10.5f) }, stroke)
                c.drawCircle(12f, 15.5f, 1.2f, fill)
            }
            Kind.UNLOCK -> {
                c.drawRoundRect(RectF(5f, 10.5f, 19f, 20.5f), 2.5f, 2.5f, stroke)
                c.drawPath(Path().apply { moveTo(8f, 10.5f); lineTo(8f, 7.5f); arcTo(RectF(8f, 3.5f, 16f, 11.5f), 180f, 150f) }, stroke)
                c.drawCircle(12f, 15.5f, 1.2f, fill)
            }
            Kind.CLIMATE -> { // thermometer with three heat lines
                c.drawPath(Path().apply { moveTo(10f, 13.8f); lineTo(10f, 5f); arcTo(RectF(10f, 3f, 14f, 7f), 180f, 180f); lineTo(14f, 13.8f) }, stroke)
                c.drawCircle(12f, 16.5f, 3.2f, stroke)
                c.drawCircle(12f, 16.5f, 1.4f, fill)
                c.drawLine(12f, 9f, 12f, 15f, stroke)
                c.drawLine(17.5f, 6f, 19.5f, 6f, stroke)
                c.drawLine(17.5f, 9f, 19.5f, 9f, stroke)
                c.drawLine(17.5f, 12f, 19.5f, 12f, stroke)
            }
            Kind.CHARGE -> {
                c.drawPath(Path().apply {
                    moveTo(13.5f, 2.8f); lineTo(5.8f, 13.4f); lineTo(11.4f, 13.4f); lineTo(10.4f, 21.2f)
                    lineTo(18.2f, 10.4f); lineTo(12.6f, 10.4f); close()
                }, stroke)
            }
            Kind.FIND -> {
                c.drawPath(Path().apply {
                    moveTo(12f, 21f)
                    cubicTo(12f, 21f, 5f, 14.6f, 5f, 9.6f)
                    arcTo(RectF(5f, 2.6f, 19f, 16.6f), 180f, 180f)
                    cubicTo(19f, 14.6f, 12f, 21f, 12f, 21f)
                    close()
                }, stroke)
                c.drawCircle(12f, 9.6f, 2.4f, stroke)
            }
            Kind.SETTINGS -> { // three sliders
                c.drawLine(4f, 7f, 20f, 7f, stroke); c.drawCircle(9f, 7f, 2.2f, bgPunch(c)); c.drawCircle(9f, 7f, 2.2f, stroke)
                c.drawLine(4f, 12f, 20f, 12f, stroke); c.drawCircle(15f, 12f, 2.2f, bgPunch(c)); c.drawCircle(15f, 12f, 2.2f, stroke)
                c.drawLine(4f, 17f, 20f, 17f, stroke); c.drawCircle(7f, 17f, 2.2f, bgPunch(c)); c.drawCircle(7f, 17f, 2.2f, stroke)
            }
            Kind.REFRESH -> {
                c.drawArc(RectF(5f, 5f, 19f, 19f), -60f, 300f, false, stroke)
                c.drawPath(Path().apply { moveTo(15.2f, 3.6f); lineTo(15.8f, 6.9f); lineTo(12.5f, 7.6f) }, stroke)
            }
            Kind.BACK -> {
                c.drawPath(Path().apply { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }, stroke)
            }
            Kind.PAUSE -> {
                c.drawLine(9f, 6.5f, 9f, 17.5f, stroke)
                c.drawLine(15f, 6.5f, 15f, 17.5f, stroke)
            }
            Kind.PLAY -> {
                c.drawPath(Path().apply { moveTo(8.5f, 6f); lineTo(18f, 12f); lineTo(8.5f, 18f); close() }, stroke)
            }
        }
        c.restore()
    }

    /** Fill behind slider knobs so the track doesn't show through. */
    private fun bgPunch(@Suppress("UNUSED_PARAMETER") c: Canvas): Paint = knobPaint
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = knob }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
