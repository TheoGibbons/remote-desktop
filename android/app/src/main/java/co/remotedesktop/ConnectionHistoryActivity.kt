package co.remotedesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The phone's side of every drop ([ConnectionLog]), newest first, with Copy
 *  to paste it next to the relay's log. */
class ConnectionHistoryActivity : AppCompatActivity() {

    private lateinit var group: LinearLayout
    private val logListener: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.init(this)
        setContentView(R.layout.activity_connection_history)
        Ui.applySystemBarInsets(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(R.string.connection_history_copy).apply {
            setIcon(R.drawable.ic_content_copy)
            iconTintList = ColorStateList.valueOf(getColor(R.color.rd_on_surface))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener { copy(); true }
        }
        group = findViewById(R.id.historyGroup)
    }

    override fun onResume() {
        super.onResume()
        ConnectionLog.listeners.add(logListener)
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        ConnectionLog.listeners.remove(logListener)
    }

    private fun copy() {
        val prefs = Prefs(this)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val text = "Remote Desktop connection history: ${prefs.deviceName}, app $version, " +
            "Android ${Build.VERSION.RELEASE}, newest first\n" + ConnectionLog.toText(ConnectionLog.entries())
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.connection_history), text))
        // Android 13+ confirms a copy itself.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.connection_history_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rebuild() {
        group.removeAllViews()
        val entries = ConnectionLog.entries()
        if (entries.isEmpty()) {
            group.addView(text(getString(R.string.connection_history_empty), secondary = true).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        for (e in entries) {
            if (group.childCount > 0) {
                group.addView(MaterialDivider(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dividerThickness)
                        .apply { marginStart = dp(42) }
                })
            }
            group.addView(row(e))
        }
    }

    private fun row(e: ConnectionLog.Entry): View {
        val dotColor = when (e.kind) {
            ConnectionLog.Kind.LOST, ConnectionLog.Kind.FAILED -> getColor(R.color.rd_danger)
            ConnectionLog.Kind.CONNECTED -> getColor(R.color.rd_good)
            else -> MaterialColors.getColor(group, com.google.android.material.R.attr.colorOutline)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ConnectionLog.title(e), appearance = com.google.android.material.R.attr.textAppearanceTitleSmall))
            // A drop's reason, or the network a NETWORK entry moved from.
            val second = if (e.kind == ConnectionLog.Kind.NETWORK) e.detail?.replaceFirstChar { it.uppercase() } else e.reason
            second?.let { addView(text(it)) }
            addView(text(listOfNotNull(timeOf(e), e.network).joinToString(" · "), secondary = true))
            if (e.kind != ConnectionLog.Kind.NETWORK) {
                e.detail?.let {
                    addView(text(it, secondary = true).apply {
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    })
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(ImageView(this@ConnectionHistoryActivity).apply {
                setImageResource(R.drawable.status_dot)
                setColorFilter(dotColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    topMargin = dp(5)
                    marginEnd = dp(16)
                }
            })
            addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** "Nov 1, 2:56:25 AM", or a range for merged tries. Seconds, to match the relay's log. */
    private fun timeOf(e: ConnectionLog.Entry): String {
        val h24 = DateFormat.is24HourFormat(this)
        val day = format(if (h24) "MMMdHHmmss" else "MMMdhmmssa")
        if (e.count <= 1) return day.format(Date(e.atMs))
        return day.format(Date(e.atMs)) + " – " + format(if (h24) "HHmmss" else "hmmssa").format(Date(e.untilMs))
    }

    private fun format(skeleton: String) =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault())

    private fun text(
        value: String,
        secondary: Boolean = false,
        appearance: Int = if (secondary) com.google.android.material.R.attr.textAppearanceBodySmall
            else com.google.android.material.R.attr.textAppearanceBodyMedium,
    ) = TextView(this).apply {
        text = value
        val style = TypedValue()
        if (theme.resolveAttribute(appearance, style, true)) setTextAppearance(style.resourceId)
        if (secondary) {
            setTextColor(MaterialColors.getColor(group, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }
}
