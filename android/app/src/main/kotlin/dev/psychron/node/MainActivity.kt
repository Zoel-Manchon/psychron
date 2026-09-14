package dev.psychron.node

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * The node's own face: what it measures, whether it is connected, and exactly what
 * leaves the phone. The last JSON message is shown verbatim, because "only summaries
 * leave the device" is a claim, and the literal message is the evidence for it.
 */
class MainActivity : Activity() {

    private val ground = Color.parseColor("#131317")
    private val ink = Color.parseColor("#ECE7DD")
    private val dim = Color.parseColor("#8C8880")
    private val accent = Color.parseColor("#D4623A")

    private lateinit var status: TextView
    private lateinit var counters: TextView
    private lateinit var readings: TextView
    private lateinit var wire: TextView
    private lateinit var toggle: Button
    private lateinit var provisioning: TextView

    private val listener: (NodeBus.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kept on while this screen is in front, for a node being watched. The
        // service keeps sampling with the screen off regardless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ground)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        column.addView(text("ENVIRONMENTAL STATION · PHONE NODE", 10f, dim, spacing = 0.16f))
        column.addView(text("Psychron", 26f, ink, bold = true).also { it.setPadding(0, dp(2), 0, dp(14)) })

        provisioning = text("", 12f, accent)
        column.addView(provisioning)

        status = text("", 13f, ink)
        column.addView(status)
        counters = text("", 11f, dim)
        column.addView(counters)

        toggle = Button(this).apply {
            setTextColor(ground)
            setBackgroundColor(ink)
            typeface = Typeface.MONOSPACE
            isAllCaps = true
            setOnClickListener { onToggle() }
        }
        column.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16); bottomMargin = dp(20) })

        column.addView(rule())
        column.addView(text("MEASURED THIS WINDOW", 10f, dim, spacing = 0.16f).also { it.setPadding(0, dp(12), 0, dp(6)) })
        readings = text("", 15f, ink)
        column.addView(readings)

        column.addView(rule().also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18) })
        column.addView(text("WHAT LEAVES THE PHONE · LAST MESSAGE", 10f, dim, spacing = 0.16f)
            .also { it.setPadding(0, dp(12), 0, dp(6)) })
        wire = text("", 11f, accent)
        column.addView(wire)
        column.addView(text("Window summaries only. Audio is reduced to two numbers in memory and never stored or sent.",
                            10f, dim).also { it.setPadding(0, dp(10), 0, 0) })

        val root = ScrollView(this).apply { setBackgroundColor(ground); addView(column) }
        // Targeting Android 15 and later, the app is drawn edge to edge whether it
        // asks or not, so the system bars are padded for here rather than coloured:
        // without this the title sits underneath the status bar.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            column.setPadding(dp(20), top + dp(20), dp(20), bottom + dp(20))
            insets
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        NodeBus.observe(listener)
    }

    override fun onStop() {
        NodeBus.forget(listener)
        super.onStop()
    }

    private fun onToggle() {
        if (NodeBus.state.running) {
            stopService(Intent(this, NodeService::class.java))
            return
        }
        if (Provisioning.missing(this).isNotEmpty()) return

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) startNode() else requestPermissions(wanted.toTypedArray(), PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Started whatever was answered. A refused microphone costs the sound group,
        // not the node: the other six sensors do not need it, and the contract says
        // an absent group means no sample rather than a failure.
        if (requestCode == PERMISSIONS) startNode()
    }

    private fun startNode() {
        startForegroundService(Intent(this, NodeService::class.java))
    }

    private fun render(s: NodeBus.State) {
        val missing = Provisioning.missing(this)
        if (missing.isNotEmpty()) {
            provisioning.visibility = View.VISIBLE
            provisioning.text = "Not provisioned. Missing: ${missing.joinToString()}\nRun infra/provision-phone.sh with the phone connected.\n"
            toggle.isEnabled = false
            toggle.text = "Provision first"
        } else {
            provisioning.visibility = View.GONE
            toggle.isEnabled = true
            toggle.text = if (s.running) "Stop node" else "Start node"
            val cfg = runCatching { Provisioning.config(this) }.getOrNull()
            if (cfg != null && !s.running) status.text = "${cfg.device} → ${cfg.host}:${cfg.port}"
        }

        if (s.running) {
            status.text = s.link
            status.setTextColor(if (s.link.startsWith("connected")) ink else accent)
        } else {
            status.setTextColor(ink)
        }
        counters.text = "sent ${s.sent} · replayed ${s.replayed} · queued ${s.queued} · dropped ${s.dropped}" +
            if (s.running && !s.microphone) " · microphone not granted" else ""

        val m = s.latest
        readings.text = if (m == null) "—" else listOf(
            row("pressure", m.pressureHpa, 2, "hPa"),
            row("light", m.illuminanceLux, 0, "lx"),
            row("sound", m.soundRmsDbfs, 1, "dBFS"),
            row("accel", m.accelRms, 3, "m/s²"),
            row("rotation", m.gyroRms, 3, "rad/s"),
            row("field", m.magneticUt, 1, "µT"),
            row("heading", m.headingDeg?.let(Contract::normaliseHeading), 0, "°"),
            row("battery", m.batteryTempC, 1, "°C"),
        ).joinToString("\n")

        wire.text = s.lastJson ?: "nothing sent yet"
    }

    private fun row(name: String, v: Double?, decimals: Int, unit: String): String {
        // Locale.ROOT: a Spanish phone would otherwise write 1013,42 on a screen whose
        // every other number, and the JSON beneath it, uses a decimal point.
        val value = v?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "—"
        return "${name.padEnd(9)} ${value.padStart(9)} $unit"
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = spacing
            gravity = Gravity.START
        }

    private fun rule() = View(this).apply {
        setBackgroundColor(Color.parseColor("#33ECE7DD"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private const val PERMISSIONS = 7 }
}
