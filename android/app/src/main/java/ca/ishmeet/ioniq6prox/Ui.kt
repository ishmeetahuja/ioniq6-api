package ca.ishmeet.ioniq6prox

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design tokens and small view builders. No libraries: plain framework views, styled in code.
 *
 * Colour carries state: glacier = locked / safe, amber = unlocked / needs attention, coral = error.
 */
object Ui {
    // Base: ink-graphite (not flat black, so a black car still reads against it)
    const val BG = 0xFF0D121B.toInt()
    const val SURFACE = 0xFF151C28.toInt()
    const val RAISED = 0xFF1C2535.toInt()
    const val HAIRLINE = 0xFF263147.toInt()
    const val TEXT = 0xFFE9EEF5.toInt()
    const val MUTED = 0xFF8C98AC.toInt()
    const val FAINT = 0xFF5B677C.toInt()
    // State
    const val GLACIER = 0xFF8FD3FF.toInt()   // locked, OK, active
    const val AMBER = 0xFFFFB547.toInt()     // unlocked, paused, attention
    const val CORAL = 0xFFFF6B6B.toInt()     // error
    const val ON_ACCENT = 0xFF0A1622.toInt() // text on glacier/amber fills

    val display: Typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 600, false)
    val displayLight: Typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 300, false)
    val body: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)
    val medium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    val semibold: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

    fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    /** Pressable background with a ripple in the given colour. */
    fun Context.pressable(fill: Int, radius: Float, ripple: Int = alpha(TEXT, 0.14f), stroke: Int = 0, strokeColor: Int = 0): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(ripple), rounded(fill, radius, stroke, strokeColor), rounded(Color.WHITE, radius))

    fun Context.text(t: CharSequence, size: Float, color: Int = TEXT, face: Typeface = body): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** A small rounded status pill: dot + label. */
    fun Context.chip(): TextView = TextView(this).apply {
        textSize = 13f
        typeface = medium
        includeFontPadding = false
        setPadding(dp(10), dp(7), dp(12), dp(7))
        compoundDrawablePadding = dp(6)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun TextView.setChip(label: String, color: Int) {
        text = label
        setTextColor(color)
        background = rounded(alpha(color, 0.12f), context.dpf(999f))
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(context.dp(7), context.dp(7)) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    /** Filled pill button. */
    fun Context.pillButton(label: String, fill: Int = RAISED, textColor: Int = TEXT, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            typeface = semibold
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = pressable(fill, dpf(999f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Vertical card container. */
    fun Context.card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, dpf(22f))
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    fun Context.field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            isSingleLine = true
            textSize = 15f
            typeface = body
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            background = rounded(RAISED, dpf(14f))
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

    fun Context.row(spacing: Int, vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = spacing
            })
        }
    }

    /** Dark status/navigation bars with light icons, content drawn edge to edge. */
    fun Activity.darkWindow() {
        window.decorView.setBackgroundColor(BG)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    fun localTime(iso: String?): String? = if (iso == null) null else try {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) { iso }
}
