package co.remotedesktop

import org.json.JSONObject

/**
 * Turns what this phone's keyboard did to a hidden text field into keys for
 * the device being viewed: Backspace for each character removed from the end,
 * then the new text, with every line break sent as Enter.
 */
internal object TypingDiff {

    sealed interface Edit
    data object Backspace : Edit
    data object Enter : Edit
    data class Text(val text: String) : Edit

    /** The edits that take the remote field from [prev] to [cur]. */
    fun edits(prev: String, cur: String): List<Edit> {
        var p = 0
        val min = minOf(prev.length, cur.length)
        while (p < min && prev[p] == cur[p]) p++
        val edits = ArrayList<Edit>()
        repeat(prev.length - p) { edits += Backspace }
        val buf = StringBuilder()
        for (ch in cur.substring(p)) {
            if (ch == '\n') {
                if (buf.isNotEmpty()) { edits += Text(buf.toString()); buf.clear() }
                edits += Enter
            } else buf.append(ch)
        }
        if (buf.isNotEmpty()) edits += Text(buf.toString())
        return edits
    }

    /** [edits] as the `key` and `text` messages a viewer sends. */
    fun messages(prev: String, cur: String): List<JSONObject> = edits(prev, cur).map {
        when (it) {
            Backspace -> JSONObject().put("type", "key").put("code", "BACKSPACE").put("action", "press")
            Enter -> JSONObject().put("type", "key").put("code", "ENTER").put("action", "press")
            is Text -> JSONObject().put("type", "text").put("text", it.text)
        }
    }
}
