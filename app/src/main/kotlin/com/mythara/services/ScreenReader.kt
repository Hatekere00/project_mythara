package com.mythara.services

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks an AccessibilityNodeInfo tree into a structured snapshot the
 * agent tool can read. We trim aggressively — full-fat node trees can
 * run into tens of thousands of nodes on chrome-heavy apps, and the
 * model only needs the user-meaningful surface.
 *
 * Filtering rules:
 *  - skip nodes with no text, contentDescription, or hint AND no
 *    interaction affordance (clickable / focusable / scrollable)
 *  - skip nodes outside the visible window bounds
 *  - cap depth to [MAX_DEPTH] and total emitted nodes to
 *    [MAX_NODES] — model gets the canonical surface, not the DOM
 *  - elide consecutive container nodes that only wrap a single
 *    child, mirroring how a human describes the screen
 *
 * Output is JSON-serialisable so the agent tool can return it
 * verbatim as a `role: tool` message. Includes screen bounds so the
 * model can reason about where things are relative to each other
 * (useful for future M6 tap-coordinate planning).
 */
@Singleton
class ScreenReader @Inject constructor() {

    @Serializable
    data class Snapshot(
        val packageName: String?,
        val rootText: String? = null,
        val nodeCount: Int,
        val nodes: List<Node>,
    )

    @Serializable
    data class Node(
        val id: Int,
        val cls: String? = null,
        val text: String? = null,
        val desc: String? = null,
        val hint: String? = null,
        val viewId: String? = null,
        val clickable: Boolean = false,
        val focusable: Boolean = false,
        val scrollable: Boolean = false,
        val editable: Boolean = false,
        val checked: Boolean? = null,
        /** Rect as [left, top, right, bottom] in screen pixels. */
        val bounds: List<Int>? = null,
        val childrenIdx: List<Int> = emptyList(),
    )

    /**
     * Snapshot the given root. Returns null if the root is null
     * (service not granted, no active window, or transient state).
     */
    fun snapshot(root: AccessibilityNodeInfo?): Snapshot? {
        if (root == null) return null
        val nodes = mutableListOf<Node>()
        val rootText = (root.text ?: root.contentDescription)?.toString()
        walk(root, depth = 0, into = nodes, parentChildren = null)
        return Snapshot(
            packageName = root.packageName?.toString(),
            rootText = rootText?.take(MAX_TEXT_LEN),
            nodeCount = nodes.size,
            nodes = nodes,
        )
    }

    /** Render a snapshot as compact JSON suitable for a `role: tool` message body. */
    fun render(snapshot: Snapshot): String = JSON.encodeToString(Snapshot.serializer(), snapshot)

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        into: MutableList<Node>,
        parentChildren: MutableList<Int>?,
    ) {
        if (into.size >= MAX_NODES) return
        if (depth > MAX_DEPTH) return

        val visible = node.isVisibleToUser
        val keep = visible && isMeaningful(node)

        // If a container is just a wrapper around a single child, skip
        // emitting it and pass the parent reference through — keeps the
        // output close to how a human describes the screen.
        val childCount = node.childCount
        if (!keep && childCount == 1) {
            val only = node.getChild(0) ?: return
            walk(only, depth + 1, into, parentChildren)
            only.recycle()
            return
        }

        val myIdx = if (keep) into.size else -1
        if (keep) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            into.add(
                Node(
                    id = myIdx,
                    cls = node.className?.toString()?.substringAfterLast('.'),
                    text = node.text?.toString()?.take(MAX_TEXT_LEN),
                    desc = node.contentDescription?.toString()?.take(MAX_TEXT_LEN),
                    hint = node.hintText?.toString()?.take(MAX_TEXT_LEN),
                    viewId = node.viewIdResourceName?.substringAfterLast('/'),
                    clickable = node.isClickable,
                    focusable = node.isFocusable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                ),
            )
            parentChildren?.add(myIdx)
        }

        if (childCount > 0) {
            val myChildren: MutableList<Int>? = if (keep) mutableListOf() else parentChildren
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1, into, myChildren)
                child.recycle()
            }
            if (keep && myChildren != null) {
                // Patch in childrenIdx now that we know it.
                into[myIdx] = into[myIdx].copy(childrenIdx = myChildren)
            }
        }
    }

    private fun isMeaningful(node: AccessibilityNodeInfo): Boolean {
        if (!node.text.isNullOrBlank()) return true
        if (!node.contentDescription.isNullOrBlank()) return true
        if (!node.hintText.isNullOrBlank()) return true
        if (node.isClickable || node.isLongClickable) return true
        if (node.isScrollable) return true
        if (node.isEditable) return true
        if (node.isCheckable) return true
        return false
    }

    companion object {
        private const val MAX_DEPTH = 30
        private const val MAX_NODES = 200
        private const val MAX_TEXT_LEN = 240
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
