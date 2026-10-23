package ca.ishmeet.ioniq6prox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * The hero: either the owner's own photo, or a generic dark sedan illustration (deliberately not
 * any real model's design). Underneath sits a soft pool of light in the state colour —
 * glacier when locked, amber when unlocked — readable from across a parking lot.
 */
class CarArtView(context: Context) : View(context) {

    var glowColor: Int = Ui.FAINT
        set(v) { if (field != v) { field = v; glowShader = null; invalidate() } }

    var photo: Bitmap? = null
        set(v) { field = v; photoShader = null; requestLayout(); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var glowShader: Shader? = null
    private var photoShader: BitmapShader? = null

    // Illustration paths in a 108×44 design space (x −4…104, y 4…48), built once.
    private val body = Path().apply {
        moveTo(95f, 31f)
        cubicTo(97f, 30.4f, 97.6f, 27.5f, 97.2f, 25.4f)
        cubicTo(96.9f, 23.6f, 95.8f, 22.4f, 93.5f, 21.8f)
        cubicTo(89f, 20.7f, 80f, 19.6f, 70f, 18.6f)
        cubicTo(66f, 15.4f, 60.5f, 11.6f, 55.5f, 10.6f)
        cubicTo(49f, 9.5f, 41f, 9.6f, 36.5f, 10.6f)
        cubicTo(32f, 11.8f, 27.5f, 14.6f, 23.5f, 16.6f)
        cubicTo(18f, 17.1f, 12.5f, 17.4f, 8.5f, 17.9f)
        cubicTo(6.4f, 18.3f, 5.4f, 19.4f, 5.1f, 21.2f)
        cubicTo(4.8f, 23.6f, 5f, 28f, 6.6f, 31f)
        lineTo(15.4f, 31f)
        arcTo(RectF(15.4f, 22.4f, 32.6f, 39.6f), 180f, 180f)
        lineTo(69.4f, 31f)
        arcTo(RectF(69.4f, 22.4f, 86.6f, 39.6f), 180f, 180f)
        close()
    }
    private val roofline = Path().apply {
        moveTo(93.5f, 21.8f)
        cubicTo(89f, 20.7f, 80f, 19.6f, 70f, 18.6f)
        cubicTo(66f, 15.4f, 60.5f, 11.6f, 55.5f, 10.6f)
        cubicTo(49f, 9.5f, 41f, 9.6f, 36.5f, 10.6f)
        cubicTo(32f, 11.8f, 27.5f, 14.6f, 23.5f, 16.6f)
        cubicTo(18f, 17.1f, 12.5f, 17.4f, 8.5f, 17.9f)
    }
    private val shoulder = Path().apply {
        moveTo(92f, 22.6f); cubicTo(80f, 21.2f, 62f, 19.8f, 40f, 19.4f); cubicTo(28f, 19.2f, 16f, 19.4f, 8f, 20.2f)
    }
    private val glass = Path().apply {
        moveTo(66.8f, 18.2f)
        cubicTo(62.5f, 15.2f, 58.5f, 12.6f, 54.8f, 11.8f)
        cubicTo(48.8f, 10.9f, 41.5f, 11f, 37.4f, 11.9f)
        cubicTo(33.6f, 12.9f, 30f, 15f, 27f, 17.1f)
        cubicTo(40f, 17.5f, 54f, 17.8f, 66.8f, 18.2f)
        close()
    }
    private val headLight = Path().apply { moveTo(90.2f, 21.2f); cubicTo(92.6f, 21.6f, 95f, 22.2f, 96.4f, 22.9f) }
    private val tailLight = Path().apply { moveTo(5.8f, 20.6f); cubicTo(7.4f, 19.9f, 9.6f, 19.5f, 12.2f, 19.3f) }

    private val bodyShader = LinearGradient(0f, 9.5f, 0f, 31f,
        intArrayOf(0xFF46546C.toInt(), 0xFF232C3B.toInt(), 0xFF151B26.toInt(), 0xFF2A3446.toInt(), 0xFF0B0F16.toInt()),
        floatArrayOf(0f, 0.3f, 0.62f, 0.78f, 1f), Shader.TileMode.CLAMP)
    private val glassShader = LinearGradient(27f, 11f, 67f, 18.2f,
        intArrayOf(0xFF1A2433.toInt(), 0xFF0C121B.toInt(), 0xFF202C3E.toInt(), 0xFF0A0F17.toInt()),
        floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (photo != null) (w * 0.62f).toInt() else (w * 44f / 108f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glowShader = null; photoShader = null
    }

    override fun onDraw(c: Canvas) {
        if (photo != null) drawPhoto(c) else drawIllustration(c)
    }

    private fun drawIllustration(c: Canvas) {
        val sc = width / 108f
        c.save()
        c.scale(sc, sc)
        c.translate(4f, -4f)

        // light pool (ellipse via scaled circle)
        if (glowShader == null) glowShader = RadialGradient(0f, 0f, 49f,
            intArrayOf(Ui.alpha(glowColor, 0.55f), Ui.alpha(glowColor, 0.16f), Ui.alpha(glowColor, 0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.translate(51f, 40.5f); c.scale(1f, 5.2f / 49f)
        p.shader = glowShader; c.drawCircle(0f, 0f, 49f, p); p.shader = null
        c.restore()

        p.shader = bodyShader; c.drawPath(body, p); p.shader = null
        s.strokeWidth = 0.35f; s.color = Ui.alpha(0xFFB9CBE4.toInt(), 0.55f); c.drawPath(roofline, s)
        s.strokeWidth = 0.45f; s.color = Ui.alpha(0xFF8FA3BF.toInt(), 0.32f); c.drawPath(shoulder, s)
        p.shader = glassShader; c.drawPath(glass, p); p.shader = null
        s.strokeWidth = 1.1f; s.color = Ui.BG; s.strokeCap = Paint.Cap.BUTT; c.drawLine(47.2f, 11.2f, 46.4f, 17.7f, s); s.strokeCap = Paint.Cap.ROUND
        s.strokeWidth = 0.7f; s.color = Ui.alpha(0xFFE9EEF5.toInt(), 0.85f); c.drawPath(headLight, s)
        s.strokeWidth = 0.55f; s.color = Ui.alpha(0xFFFF5A5A.toInt(), 0.75f); c.drawPath(tailLight, s)
        s.strokeWidth = 0.25f; s.color = Ui.alpha(0xFF05080C.toInt(), 0.7f); c.drawLine(47.5f, 18.4f, 47.8f, 30f, s)
        s.strokeWidth = 0.45f; s.color = Ui.alpha(0xFF7F8DA3.toInt(), 0.55f)
        c.drawLine(52f, 21.6f, 56f, 21.6f, s); c.drawLine(36f, 21.4f, 40f, 21.4f, s)
        wheel(c, 24f); wheel(c, 78f)
        c.restore()
    }

    private fun wheel(c: Canvas, cx: Float) {
        val cy = 33f
        p.color = 0xFF06080C.toInt(); c.drawCircle(cx, cy, 7.2f, p)
        p.shader = RadialGradient(cx, cy, 5f, 0xFF3A4558.toInt(), 0xFF1A212C.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 5f, p); p.shader = null
        s.strokeWidth = 0.7f; s.color = 0xFF55627A.toInt()
        for (i in 0 until 5) {
            val a = Math.toRadians(-90.0 + i * 72.0)
            c.drawLine(cx, cy, cx + (4.4 * Math.cos(a)).toFloat(), cy + (4.4 * Math.sin(a)).toFloat(), s)
        }
        p.color = Ui.BG; c.drawCircle(cx, cy, 1.1f, p)
    }

    private fun drawPhoto(c: Canvas) {
        val bmp = photo ?: return
        val r = context.resources.displayMetrics.density * 24f
        if (photoShader == null) {
            photoShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val scale = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
                val dx = (width - bmp.width * scale) / 2f
                val dy = (height - bmp.height * scale) / 2f
                setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
            }
        }
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        p.shader = photoShader; c.drawRoundRect(rect, r, r, p)
        // fade the bottom into the page, then a band of state colour along the bottom edge
        p.shader = LinearGradient(0f, height * 0.55f, 0f, height.toFloat(), Ui.alpha(Ui.BG, 0f), Ui.alpha(Ui.BG, 0.92f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        p.shader = LinearGradient(0f, height * 0.78f, 0f, height.toFloat(), Ui.alpha(glowColor, 0f), Ui.alpha(glowColor, 0.30f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
    }
}
