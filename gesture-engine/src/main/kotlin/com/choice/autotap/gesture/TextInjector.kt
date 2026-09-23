package com.choice.autotap.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.choice.autotap.model.PixelPoint

/** Puts text into editable fields: ACTION_SET_TEXT (direct) or clipboard + paste. */
class TextInjector(
    private val context: Context,
    private val nodes: NodeFinder,
) {

    /**
     * Sets [text] on the focused editable node, or the editable node under [target].
     * Works with any Unicode text, including Arabic/RTL and emoji.
     */
    fun setText(text: CharSequence, target: PixelPoint?, append: Boolean): Boolean {
        val node = nodes.findFocusedEditable()
            ?: target?.let { nodes.findEditableAt(it.x.toInt(), it.y.toInt()) }
            ?: return false
        val existing = if (append && !isShowingHint(node)) node.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun copyToClipboard(text: CharSequence) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Choice Auto Tap", text))
    }

    /** ACTION_PASTE on the focused editable node (fallback when the popup can't be found). */
    fun pasteIntoFocused(): Boolean =
        nodes.findFocusedEditable()?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false

    private fun isShowingHint(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
}
