package co.remotedesktop

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a viewer's menu asks of this phone besides input: its media volume,
 * and what it can do right now (`volume`, `get-phone-state` and `phone-state`
 * in PROTOCOL.md).
 *
 * None of this is input, so none of it wakes a sleeping phone. That is why
 * these messages come here and not to [InputAccessibilityService.handle].
 */
internal object PhoneControls {
    /** Handles `volume` and `get-phone-state` from a device that may control
     *  this phone, and tells it how things stand afterwards. */
    fun handle(context: Context, from: String, msg: JSONObject) {
        if (msg.optString("type") == "volume") setVolume(context, msg)
        ConnectionManager.sendJson(state(context).put("to", from))
    }

    /** This phone's `phone-state`, without its `to`. */
    fun state(context: Context): JSONObject {
        val audio = audio(context)
        return state(
            // Android reports 0 while the stream is muted.
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
            volumeFixed = audio.isVolumeFixed,
            actions = InputAccessibilityService.menuActions,
        )
    }

    /**
     * Builds `phone-state`. `can` lists what a viewer may offer: `volume`
     * unless the device has none to set (a TV box, a fixed-volume output),
     * then the actions the accessibility service can perform.
     */
    fun state(volume: Int, volumeMax: Int, muted: Boolean, volumeFixed: Boolean, actions: List<String>): JSONObject {
        val can = JSONArray()
        if (!volumeFixed && volumeMax > 0) can.put("volume")
        actions.forEach(can::put)
        return JSONObject()
            .put("type", "phone-state")
            .put("volume", volume)
            .put("volumeMax", volumeMax)
            .put("muted", muted)
            // Nothing holds the phone's orientation yet.
            .put("rotation", "auto")
            .put("can", can)
    }

    /** Media volume, with the phone's own volume panel shown as it changes,
     *  as when its volume keys are pressed. */
    private fun setVolume(context: Context, msg: JSONObject) {
        val audio = audio(context)
        if (audio.isVolumeFixed) return
        try {
            // A level or a mute that isn't what it should be changes nothing.
            (msg.opt("level") as? Number)?.let { level ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.toInt().coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            }
            (msg.opt("mute") as? Boolean)?.let { mute ->
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        } catch (_: SecurityException) {
            // Do Not Disturb can refuse a change. The answer says where the volume really is.
        }
    }

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
