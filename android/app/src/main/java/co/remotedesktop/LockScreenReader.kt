package co.remotedesktop

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds where this phone's pattern, PIN or password is entered while its
 * unlock screen is up, so a viewer can draw the grid or the keys itself:
 * Android leaves that screen out of every picture (`entry` and `message` of
 * `lock-state` in PROTOCOL.md).
 *
 * It is the only thing in the app that reads what is on the screen, and it
 * is kept narrow. [LockState] asks only while the phone is locked, its
 * screen is on and an approved device is viewing; and only a window of the
 * system's own interface, where the lock screen lives, is ever walked. What
 * comes out is where the grid or the keys are and the lock screen's own
 * message line. Nothing is kept, and nothing that is entered is seen.
 *
 * In two halves so the deciding half can be tested without a phone:
 * [walk] turns a window into a plain list, and [find] reads the list.
 */
internal object LockScreenReader {
    /** The package that draws the lock screen and the unlock screen. */
    const val LOCK_SCREEN_PACKAGE = "com.android.systemui"

    /** One view of the window, as plain values. Bounds are screen pixels. */
    data class Node(
        val id: String? = null,
        val className: String? = null,
        val text: String? = null,
        val description: String? = null,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val clickable: Boolean = false,
        val editable: Boolean = false,
    ) {
        /** The id without its package: `lockPatternView` of `com.android.systemui:id/lockPatternView`. */
        val idName: String get() = id?.substringAfterLast('/') ?: ""
    }

    /** A rectangle as fractions of the screen, the way `tap` gives a point. */
    data class Area(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun toList(): List<Double> = listOf(x, y, width, height)
    }

    data class Key(val label: String, val rect: Area)

    sealed class Entry {
        /** The pattern grid, as one square: Android does not report its dots. */
        data class Pattern(val area: Area) : Entry()
        /** The keys of a PIN pad: the ten digits, and `delete` and `ok` where the phone has them. */
        data class Pin(val keys: List<Key>) : Entry()
        /** A password field. There is nothing to draw; it is typed into. */
        object Password : Entry()
    }

    /** What was found: nothing at all on any screen that isn't an unlock screen. */
    data class Found(val entry: Entry? = null, val message: String? = null) {
        companion object {
            val NOTHING = Found()
        }
    }

    const val DELETE = "delete"
    const val OK = "ok"

    private val digitKeyId = Regex("key(\\d)")
    private const val MESSAGE_MAX = 200

    /**
     * Reads the list [walk] made of a window, on a screen [width] by
     * [height] pixels.
     *
     * - Pattern: the view whose id is `lockPatternView`.
     * - PIN: all ten digits, as keys named `key0` to `key9`, or failing that
     *   as views that say a single digit. `delete_button` and `key_enter`
     *   are Android's own names for the other two.
     * - Password: a text field.
     * - The message: the text of the views whose id ends in `message_area`.
     *
     * Anything else gives no entry, and the viewer says what it said before
     * it could be told any of this.
     */
    fun find(nodes: List<Node>, width: Int, height: Int): Found {
        if (width <= 0 || height <= 0) return Found.NOTHING
        fun area(node: Node): Area? {
            if (node.right <= node.left || node.bottom <= node.top) return null
            if (node.left < 0 || node.top < 0 || node.right > width || node.bottom > height) return null
            return Area(fraction(node.left, width), fraction(node.top, height),
                fraction(node.right - node.left, width), fraction(node.bottom - node.top, height))
        }

        val message = nodes.filter { it.idName.endsWith("message_area", ignoreCase = true) }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            .distinct().joinToString(" ").take(MESSAGE_MAX).takeIf { it.isNotEmpty() }

        nodes.firstOrNull { it.idName == "lockPatternView" }?.let(::area)?.let {
            return Found(Entry.Pattern(it), message)
        }

        pinKeys(nodes, ::area)?.let { return Found(Entry.Pin(it), message) }

        if (nodes.any { it.editable && area(it) != null }) return Found(Entry.Password, message)
        return Found(null, message)
    }

    /** The keys of a PIN pad in the order 1 to 9, 0, delete, ok; null unless all ten digits are there. */
    private fun pinKeys(nodes: List<Node>, area: (Node) -> Area?): List<Key>? {
        val keys = HashMap<String, Area>()
        // Best first: a key known by its id, then something to press that
        // says what it is, then a bare digit drawn on a key.
        val passes: List<(Node) -> String?> = listOf(
            { node ->
                digitKeyId.matchEntire(node.idName)?.groupValues?.get(1) ?: when (node.idName) {
                    "delete_button" -> DELETE
                    "key_enter" -> OK
                    else -> null
                }
            },
            { node -> if (node.clickable) digit(node.text) ?: digit(node.description) ?: named(node.description) else null },
            { node -> digit(node.text) },
        )
        for (label in passes) for (node in nodes) {
            val name = label(node) ?: continue
            if (name !in keys) area(node)?.let { keys[name] = it }
        }
        if ((0..9).any { it.toString() !in keys }) return null
        val order = (1..9).map { it.toString() } + listOf("0", DELETE, OK)
        return order.mapNotNull { name -> keys[name]?.let { Key(name, it) } }
    }

    private fun digit(text: String?): String? = text?.trim()?.takeIf { it.length == 1 && it[0] in '0'..'9' }

    private fun named(description: String?): String? = when (description?.trim()?.lowercase()) {
        "delete", "backspace" -> DELETE
        "enter", "ok", "done" -> OK
        else -> null
    }

    /** To four places: a tenth of a pixel on any phone, and short on the wire. */
    private fun fraction(pixels: Int, of: Int): Double = Math.round(pixels * 10_000.0 / of) / 10_000.0

    // ---------- the half that touches Android ----------

    /** A lock screen has a few dozen views. More than this is not one. */
    private const val MAX_NODES = 400

    /**
     * What the window in front says about entering the pattern or PIN, on a
     * screen [width] by [height] pixels. Nothing unless that window belongs
     * to [LOCK_SCREEN_PACKAGE]: no other app's window is walked.
     */
    fun read(service: AccessibilityService, width: Int, height: Int): Found {
        val root = try {
            service.rootInActiveWindow
        } catch (e: RuntimeException) {
            // E.g. the service was switched off a moment ago.
            null
        } ?: return Found.NOTHING
        if (root.packageName?.toString() != LOCK_SCREEN_PACKAGE) return Found.NOTHING
        val nodes = walk(root)
        val found = find(nodes, width, height)
        if (BuildConfig.DEBUG) describe(nodes, found)
        return found
    }

    /** Every view of a window that can be seen, as a plain list. */
    fun walk(root: AccessibilityNodeInfo): List<Node> {
        val nodes = ArrayList<Node>()
        val bounds = Rect()
        fun visit(node: AccessibilityNodeInfo) {
            if (nodes.size >= MAX_NODES || !node.isVisibleToUser) return
            node.getBoundsInScreen(bounds)
            nodes.add(Node(
                id = node.viewIdResourceName,
                className = node.className?.toString(),
                text = node.text?.toString(),
                description = node.contentDescription?.toString(),
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
                clickable = node.isClickable,
                editable = node.isEditable,
            ))
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        try {
            visit(root)
        } catch (e: RuntimeException) {
            // The window went away mid-walk. What was read of it still counts.
        }
        return nodes
    }

    /**
     * Debug builds only: what was walked, for checking a phone whose lock
     * screen names things differently. Ids and places, never what a view
     * says (a lock screen shows notifications): only how long its text is.
     */
    private fun describe(nodes: List<Node>, found: Found) {
        Log.d("LockScreenReader", "Walked ${nodes.size} views: entry=${found.entry} message=${found.message != null}")
        for (node in nodes) {
            if (node.idName.isEmpty() && !node.clickable && !node.editable) continue
            Log.d("LockScreenReader", "  ${node.idName} ${node.className?.substringAfterLast('.')} " +
                "[${node.left},${node.top}][${node.right},${node.bottom}]" +
                (if (node.clickable) " clickable" else "") + (if (node.editable) " editable" else "") +
                " text=${node.text?.length ?: 0} description=${node.description?.length ?: 0}")
        }
    }
}
