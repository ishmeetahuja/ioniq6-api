package ca.ishmeet.ioniq6prox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Live tag signal (−100…−40 dBm) as a bar, with a tick at the walk-away threshold. */
class SignalMeterView(context: Context) : View(context) {

    var rssi: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var threshold: Int = -85
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (18 * d).toInt())
    }

    private fun frac(dbm: Int) = ((dbm + 100f) / 60f).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val h = 8 * d
        val top = (height - h) / 2f
        val r = h / 2f
        p.color = Ui.RAISED
        c.drawRoundRect(RectF(0f, top, width.toFloat(), top + h), r, r, p)
        rssi?.let {
            val w = (width * frac(it)).coerceAtLeast(h)
            p.color = if (it >= threshold) Ui.GLACIER else Ui.AMBER
            c.drawRoundRect(RectF(0f, top, w, top + h), r, r, p)
        }
        val x = width * frac(threshold)
        p.color = Ui.TEXT
        c.drawRoundRect(RectF(x - d, 0f, x + d, height.toFloat()), d, d, p)
    }
}
