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
 * system's own interface, where the lock screen lives, is ever walked.
 *
 * Of each view in that window it takes where the view is, what it is called
 * and whether it can be pressed or typed into. What a view says is looked
 * at in two cases only: the lock screen's message line, and something to
 * press that says a digit or names a key ([messageText], [keyWord]). The
 * words of anything else are never asked for: not a notification, not a
 * clock, and never a field that is typed into. What comes out is where the
 * grid or the keys are, and the message line. Nothing is kept.
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
        /** A field that hides what is typed into it. */
        val password: Boolean = false,
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

    /** One look at the window in front. */
    sealed class Read {
        /** The window was read from end to end, and this is what it holds;
         *  or an app's window is in front of the lock screen, which holds
         *  nothing of the lock screen's. */
        data class Complete(val found: Found) : Read()

        /** There was no window to read, or the walk broke off or was cut
         *  short. Nothing is known: not that there is a grid, and not that
         *  there is none. */
        object Unknown : Read()
    }

    /** What [walk] got through, and whether that was all of it. */
    class Walk(val nodes: List<Node>, val complete: Boolean)

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
     *   as things to press that say a single digit. `delete_button` and
     *   `key_enter` are Android's own names for the other two.
     * - Password: a field that is typed into.
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

        val message = nodes.filter { isMessage(it.idName) }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            .distinct().joinToString(" ").take(MESSAGE_MAX).takeIf { it.isNotEmpty() }

        nodes.firstOrNull { it.idName == "lockPatternView" }?.let(::area)?.let {
            return Found(Entry.Pattern(it), message)
        }

        pinKeys(nodes, ::area)?.let { return Found(Entry.Pin(it), message) }

        if (nodes.any { (it.editable || it.password) && area(it) != null }) return Found(Entry.Password, message)
        return Found(null, message)
    }

    /** The keys of a PIN pad in the order 1 to 9, 0, delete, ok; null unless all ten digits are there. */
    private fun pinKeys(nodes: List<Node>, area: (Node) -> Area?): List<Key>? {
        val keys = HashMap<String, Area>()
        // Best first: a key known by its id, then something to press that
        // says what it is. A digit that is merely on show (a badge counting
        // notifications, a clock) is not a key, and a field is never one.
        val passes: List<(Node) -> String?> = listOf(
            { node ->
                digitKeyId.matchEntire(node.idName)?.groupValues?.get(1) ?: when (node.idName) {
                    "delete_button" -> DELETE
                    "key_enter" -> OK
                    else -> null
                }
            },
            { node ->
                if (!node.clickable || node.editable || node.password) null
                else digit(node.text) ?: digit(node.description) ?: named(node.description) ?: named(node.text)
            },
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

    private fun isMessage(idName: String) = idName.endsWith("message_area", ignoreCase = true)

    /**
     * The lock screen's message line, if the view called [idName] is it;
     * otherwise null, and [read], which fetches what the view says, is not
     * called. A field that is typed into is never read, whatever it is called.
     */
    fun messageText(idName: String, editable: Boolean, password: Boolean, read: () -> String?): String? {
        if (editable || password || !isMessage(idName)) return null
        return read()?.trim()?.take(MESSAGE_MAX)?.takeIf { it.isNotEmpty() }
    }

    /**
     * What a view says, if it is something to press that says a digit or
     * names a key ("Delete", "Enter"); otherwise null. [read] is called only
     * for a view that can be pressed and is not typed into, and what it
     * returns is dropped at once unless it is such a word.
     */
    fun keyWord(clickable: Boolean, editable: Boolean, password: Boolean, read: () -> String?): String? {
        if (!clickable || editable || password) return null
        val said = read()?.trim() ?: return null
        return said.takeIf { digit(it) != null || named(it) != null }
    }

    /** To four places: a tenth of a pixel on any phone, and short on the wire. */
    private fun fraction(pixels: Int, of: Int): Double = Math.round(pixels * 10_000.0 / of) / 10_000.0

    // ---------- the half that touches Android ----------

    /** A lock screen has a few dozen views. More than this is not one. */
    private const val MAX_NODES = 400

    /**
     * What the window in front says about entering the pattern or PIN, on a
     * screen [width] by [height] pixels. No window but one that belongs to
     * [LOCK_SCREEN_PACKAGE] is walked: with an app in front (a call, the
     * camera over the lock screen) the answer is that nothing is there.
     * Safe to call off the main thread.
     */
    fun read(service: AccessibilityService, width: Int, height: Int): Read {
        val root = try {
            service.rootInActiveWindow
        } catch (e: RuntimeException) {
            // E.g. the service was switched off a moment ago.
            null
        } ?: return Read.Unknown // Windows are changing hands; there will be one in a moment.
        if (root.packageName?.toString() != LOCK_SCREEN_PACKAGE) return Read.Complete(Found.NOTHING)
        val walk = walk(root)
        if (!walk.complete) return Read.Unknown
        val found = find(walk.nodes, width, height)
        if (BuildConfig.DEBUG) describe(walk.nodes, found)
        return Read.Complete(found)
    }

    /**
     * Every view of a window that can be seen, as a plain list: where each
     * is and what it is called, and what it says only where [messageText]
     * and [keyWord] allow.
     */
    fun walk(root: AccessibilityNodeInfo): Walk {
        val nodes = ArrayList<Node>()
        val bounds = Rect()
        var complete = true
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (nodes.size >= MAX_NODES) {
                complete = false
                return
            }
            node.getBoundsInScreen(bounds)
            val id = node.viewIdResourceName
            val clickable = node.isClickable
            val editable = node.isEditable
            val password = node.isPassword
            nodes.add(Node(
                id = id,
                className = node.className?.toString(),
                text = messageText(id?.substringAfterLast('/') ?: "", editable, password) { node.text?.toString() }
                    ?: keyWord(clickable, editable, password) { node.text?.toString() },
                description = keyWord(clickable, editable, password) { node.contentDescription?.toString() },
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
                clickable = clickable,
                editable = editable,
                password = password,
            ))
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        try {
            visit(root)
        } catch (e: RuntimeException) {
            // The window went away mid-walk. Half a window is not a window
            // with half its keys missing: it is one that wasn't read.
            complete = false
        }
        return Walk(nodes, complete)
    }

    /**
     * Debug builds only: what was walked, for checking a phone whose lock
     * screen names things differently. Ids and places, never what a view
     * says: only how long the little that was taken of it is.
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
