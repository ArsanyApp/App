package com.choice.autotap.gesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Searches the accessibility node trees of all visible windows. */
class NodeFinder(private val service: AccessibilityService) {

    /** Roots of every interactive window (needs flagRetrieveInteractiveWindows), active window first. */
    fun roots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        service.rootInActiveWindow?.let(result::add)
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            val root = w.root ?: return@forEach
            if (result.none { it == root }) result.add(root)
        }
        return result.filter { it.packageName?.toString() != service.packageName }
    }

    fun isTextVisible(text: String, exactMatch: Boolean): Boolean = findByText(text, exactMatch) != null

    /** First visible node whose text or content description matches [text]. */
    fun findByText(text: String, exactMatch: Boolean): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val needle = text.trim()
        for (root in roots()) {
            // Fast path: the framework's own (case-insensitive "contains") text search.
            val candidates = runCatching { root.findAccessibilityNodeInfosByText(needle) }.getOrNull().orEmpty()
            candidates.firstOrNull { it.isVisibleToUser && matches(it, needle, exactMatch) }?.let { return it }
            // Slow path: full traversal also covers content descriptions and nodes the fast path misses.
            traverse(root) { node -> node.isVisibleToUser && matches(node, needle, exactMatch) }?.let { return it }
        }
        return null
    }

    /** Deepest editable node whose bounds contain (x, y). */
    fun findEditableAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val rect = Rect()
        for (root in roots()) {
            traverse(root) { node ->
                if (node.isEditable) {
                    node.getBoundsInScreen(rect)
                    val area = rect.width().toLong() * rect.height()
                    if (rect.contains(x, y) && area < bestArea) {
                        best = node
                        bestArea = area
                    }
                }
                false
            }
        }
        return best
    }

    fun findFocusedEditable(): AccessibilityNodeInfo? {
        for (root in roots()) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
        }
        return null
    }

    /** Clicks [node] or its nearest clickable ancestor. */
    fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun matches(node: AccessibilityNodeInfo, needle: String, exact: Boolean): Boolean {
        val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        return values.any { v ->
            val t = v.trim()
            if (exact) t.equals(needle, ignoreCase = true) else t.contains(needle, ignoreCase = true)
        }
    }

    /** Depth-first search; returns the first node for which [predicate] is true. */
    private fun traverse(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (predicate(node)) return node
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }
        }
        return null
    }

    private companion object {
        const val MAX_NODES = 5_000
    }
}
