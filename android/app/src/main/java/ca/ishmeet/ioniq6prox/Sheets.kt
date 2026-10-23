package ca.ishmeet.ioniq6prox

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ca.ishmeet.ioniq6prox.Ui.dp
import ca.ishmeet.ioniq6prox.Ui.dpf
import ca.ishmeet.ioniq6prox.Ui.text

/** Bottom sheets and an in-app message bar, styled to match (instead of stock dialogs/toasts). */
object Sheets {

    fun Activity.sheet(title: String, subtitle: String? = null, build: LinearLayout.(Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                val r = dpf(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(22), dp(10), dp(22), dp(22))
        }
        // grab handle
        box.addView(FrameLayout(this).apply {
            addView(android.view.View(context).apply { background = Ui.rounded(Ui.HAIRLINE, dpf(3f)) },
                FrameLayout.LayoutParams(dp(36), dp(5), Gravity.CENTER))
        }, Ui.lp(h = dp(18)))
        box.addView(text(title, 22f, Ui.TEXT, Ui.semibold), Ui.lp(top = dp(8)))
        if (subtitle != null) box.addView(text(subtitle, 14f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(top = dp(6)))
        box.build(d)
        box.setOnApplyWindowInsetsListener { v, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(22) + nav)
            insets
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
            setWindowAnimations(android.R.style.Animation_InputMethod) // slides up from the bottom
            setDecorFitsSystemWindows(false)
        }
        d.show()
        return d
    }

    /** A short message that slides up above the bottom edge. One at a time. */
    class MessageBar(private val host: FrameLayout) {
        private val view: TextView = host.context.text("", 15f, Ui.TEXT, Ui.medium).apply {
            background = Ui.rounded(Ui.RAISED, host.context.dpf(16f), host.context.dp(1), Ui.HAIRLINE)
            setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
            setLineSpacing(0f, 1.15f)
            alpha = 0f
            visibility = android.view.View.GONE
        }
        private var bottomInset = 0
        private val hide = Runnable {
            view.animate().alpha(0f).translationY(view.context.dpf(16f)).setDuration(180)
                .withEndAction { view.visibility = android.view.View.GONE }.start()
        }

        init {
            host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = host.context.dp(16); setMargins(m, 0, m, m)
            })
            host.setOnApplyWindowInsetsListener { v, insets ->
                bottomInset = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                (view.layoutParams as FrameLayout.LayoutParams).bottomMargin = v.context.dp(16) + bottomInset
                insets
            }
        }

        fun show(msg: String, color: Int = Ui.TEXT, ms: Long = 3_500) {
            view.removeCallbacks(hide)
            view.text = msg
            view.setTextColor(color)
            view.visibility = android.view.View.VISIBLE
            view.translationY = view.context.dpf(16f)
            view.animate().alpha(1f).translationY(0f).setDuration(200).start()
            view.postDelayed(hide, ms)
        }
    }
}
