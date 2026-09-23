package com.choice.autotap.overlay

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.choice.autotap.overlay.OverlayWindows.safeRemove

/** A small focusable overlay dialog with one text field, shown above any app. */
internal class TextPrompt(private val context: Context, private val wm: WindowManager) {

    private var root: View? = null

    fun show(title: String, initial: String, numeric: Boolean, onResult: (String?) -> Unit) {
        dismiss()
        val input = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x99FFFFFF.toInt())
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            inputType = if (numeric) {
                InputType.TYPE_CLASS_TEXT // allows "1000-3000"
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            minWidth = context.dp(240f)
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(context.overlayButton("Cancel") { finish(null, onResult) })
            addView(View(context), LinearLayout.LayoutParams(context.dp(8f), 1))
            addView(context.overlayButton("OK") { finish(input.text.toString(), onResult) })
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(16f)
            setPadding(pad, pad, pad, pad)
            background = roundedBackground(0xEE202124.toInt(), context.dp(16f).toFloat())
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
            })
            addView(input)
            addView(buttons)
        }
        val params = OverlayWindows.params(focusable = true).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(panel, params)
        root = panel
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finish(result: String?, onResult: (String?) -> Unit) {
        dismiss()
        onResult(result)
    }

    fun dismiss() {
        root?.let { wm.safeRemove(it) }
        root = null
    }
}
