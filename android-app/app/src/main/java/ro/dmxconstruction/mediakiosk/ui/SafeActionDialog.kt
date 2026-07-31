package ro.dmxconstruction.mediakiosk.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import ro.dmxconstruction.mediakiosk.R

/**
 * Dialog fără android.widget.Button/AppCompatButton. Unele firmware-uri Rockchip
 * Android 5.1 corup accesul TypedArray la fontFamily în constructorul Button.
 */
object SafeActionDialog {
    class Handle internal constructor(
        val dialog: Dialog,
        private val messageView: TextView
    ) {
        fun setMessage(message: String) {
            messageView.text = message
            messageView.visibility = View.VISIBLE
        }
    }

    fun create(
        context: Context,
        title: String,
        message: String? = null,
        customView: View? = null,
        positiveLabel: String,
        onPositive: (Handle) -> Unit
    ): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(Color.rgb(18, 24, 32))
        }
        content.addView(TextView(context).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        val messageView = TextView(context).apply {
            text = message.orEmpty()
            textSize = 16f
            setTextColor(Color.LTGRAY)
            visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(messageView, matchWrap())
        customView?.let {
            content.addView(it, matchWrap().apply { bottomMargin = dp(12) })
        }

        val handle = Handle(dialog, messageView)
        content.addView(action(context, positiveLabel).apply {
            setOnClickListener { onPositive(handle) }
        }, matchWrap().apply {
            height = dp(52)
            bottomMargin = dp(8)
        })
        content.addView(action(context, "Anulează").apply {
            setOnClickListener { dialog.dismiss() }
        }, matchWrap().apply { height = dp(52) })

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return dialog
    }

    private fun action(context: Context, label: String) = TextView(context).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.button_background)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
