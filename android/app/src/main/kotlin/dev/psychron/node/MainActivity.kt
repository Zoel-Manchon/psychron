package dev.psychron.node

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.log10

/**
 * The node's own face: what it measures, whether it is connected, and exactly what
 * leaves the phone. The last message is shown verbatim — indented and coloured, but
 * byte for byte what went on the wire — because "only summaries leave the device"
 * is a claim, and the literal message is the evidence for it.
 */
class MainActivity : Activity() {

    private lateinit var pip: View
    private lateinit var status: TextView
    private lateinit var counters: TextView
    private lateinit var clockLine: TextView
    private lateinit var networkLine: TextView
    private lateinit var dataLine: TextView
    private lateinit var provisioning: TextView
    private lateinit var toggle: TextView
    private lateinit var windowInfo: TextView

    private lateinit var pressure: Tile
    private lateinit var light: Tile
    private lateinit var sound: Tile
    private lateinit var motion: Tile
    private lateinit var heading: Tile
    private lateinit var battery: Tile
    private lateinit var compass: CompassView

    private lateinit var wireMeta: TextView
    private lateinit var wire: TextView

    private val listener: (NodeBus.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kept on while this screen is in front, for a node being watched. The
        // service keeps sampling with the screen off regardless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ink.ground)
        }

        // ── masthead ────────────────────────────────────────────────────────
        column.addView(label("Environmental station · phone node"))
        column.addView(TextView(this).apply {
            text = "Psychron"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Ink.ink)
            typeface = Ink.monoBold
            setPadding(0, dpi(2), 0, dpi(12))
        })

        provisioning = mono(11f, Ink.accent)
        column.addView(provisioning)

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pip = View(this)
        statusRow.addView(pip, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { rightMargin = dpi(8) })
        status = mono(13f, Ink.ink)
        statusRow.addView(status)
        column.addView(statusRow)
        counters = mono(10.5f, Ink.ink3).apply { setPadding(0, dpi(4), 0, 0) }
        column.addView(counters)
        clockLine = mono(10.5f, Ink.ink3).apply { setPadding(0, dpi(2), 0, 0) }
        column.addView(clockLine)
        networkLine = mono(10.5f, Ink.ink3).apply { setPadding(0, dpi(2), 0, 0) }
        column.addView(networkLine)
        dataLine = mono(10.5f, Ink.ink3).apply { setPadding(0, dpi(2), 0, 0) }
        column.addView(dataLine)

        // A bordered control rather than a filled slab: the action matters, but
        // it is not the most important thing on a screen full of measurements.
        toggle = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Ink.monoBold
            letterSpacing = 0.18f
            isClickable = true
            setOnClickListener { onToggle() }
        }
        column.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48))
            .apply { topMargin = dpi(16); bottomMargin = dpi(22) })

        // ── measurements ────────────────────────────────────────────────────
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(label("Measured this window"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        windowInfo = mono(9.5f, Ink.ink3)
        head.addView(windowInfo)
        column.addView(head)
        column.addView(hairline().also { (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(5); bottomMargin = dpi(10) } })

        pressure = Tile(this, "Pressure", "hPa")
        light = Tile(this, "Light", "lx", meter = true)
        sound = Tile(this, "Sound", "dBFS", meter = true)
        motion = Tile(this, "Motion", "m/s²")
        compass = CompassView(this)
        heading = Tile(this, "Heading", "°", trailing = compass)
        battery = Tile(this, "Battery", "°C")

        column.addView(pair(pressure, light))
        column.addView(pair(sound, motion))
        column.addView(pair(heading, battery))

        // ── the wire ────────────────────────────────────────────────────────
        column.addView(label("What leaves the phone · last message").also { it.setPadding(0, dpi(16), 0, 0) })
        column.addView(hairline().also { (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(5); bottomMargin = dpi(8) } })
        wireMeta = mono(9.5f, Ink.ink3).apply { setPadding(0, 0, 0, dpi(8)) }
        column.addView(wireMeta)

        wire = mono(11.5f, Ink.ink).apply {
            setLineSpacing(0f, 1.22f)
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        }
        // Horizontal scroll rather than wrapping: a wrapped JSON line breaks inside
        // a value, and "178940 / 5965" reads as a different number from 1789405965.
        val well = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply { setColor(Ink.well); setStroke(dpi(1), Ink.ruleFaint) }
            addView(wire)
        }
        column.addView(well)

        column.addView(mono(9.5f, Ink.ink3).apply {
            text = "Window summaries only. Audio is reduced to two numbers in memory and never stored or sent."
            setPadding(0, dpi(10), 0, 0)
        })

        val root = ScrollView(this).apply { setBackgroundColor(Ink.ground); addView(column) }
        // Targeting Android 15 and later, the app is drawn edge to edge whether it
        // asks or not, so the system bars are padded for here rather than coloured:
        // without this the title sits underneath the status bar.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            } else {
                legacyInsets(insets)
            }
            // On the scroll view, not the column, with clipping on: padding inside
            // the column only helps at the top of the page, and once scrolled the
            // content slides under the clock and the status icons.
            root.setPadding(0, top, 0, bottom)
            insets
        }
        root.clipToPadding = true
        column.setPadding(dpi(18), dpi(18), dpi(18), dpi(22))
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
        // not the node: the other sensors do not need it, and the contract says an
        // absent group means no sample rather than a failure.
        if (requestCode == PERMISSIONS) startNode()
    }

    private fun startNode() {
        startForegroundService(Intent(this, NodeService::class.java))
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private fun render(s: NodeBus.State) {
        val missing = Provisioning.missing(this)
        val cfg = if (missing.isEmpty()) runCatching { Provisioning.config(this) }.getOrNull() else null

        provisioning.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        provisioning.text = "Not provisioned · missing ${missing.joinToString()}\nRun infra/provision-phone.sh with the phone connected.\n"

        val connected = s.running && s.link.startsWith("connected")
        // A square pip, filled when live: the panel's own status mark, so the phone
        // and the dashboard say "connected" the same way.
        pip.background = GradientDrawable().apply {
            setColor(if (connected) Ink.accent else 0)
            setStroke(dpi(1), if (connected) Ink.accent else Ink.ink2)
        }
        status.text = when {
            s.running -> s.link
            cfg != null -> "${cfg.device} → ${cfg.hosts.joinToString(" | ")} :${cfg.port}"
            else -> "not provisioned"
        }
        status.setTextColor(if (s.running && !connected) Ink.accent else Ink.ink)
        counters.text = "sent ${s.sent} · replayed ${s.replayed} · queued ${s.queued} · dropped ${s.dropped}" +
            if (s.running && !s.microphone) " · microphone not granted" else ""

        clockLine.text = if (s.running) "time · ${s.clock}" else ""
        clockLine.setTextColor(if (s.clock.startsWith("NTP")) Ink.ink3 else Ink.accent)

        networkLine.text = if (s.running) "network · ${s.network}${s.endpoint?.let { " → $it" } ?: ""}" else ""
        networkLine.setTextColor(if (s.network == "no network") Ink.accent else Ink.ink3)
        dataLine.text = if (s.running) dataUsage(s) else ""

        toggle.text = when {
            missing.isNotEmpty() -> "PROVISION FIRST"
            s.running -> "STOP NODE"
            else -> "START NODE"
        }
        toggle.isEnabled = missing.isEmpty()
        toggle.setTextColor(if (s.running) Ink.accent else Ink.ink)
        toggle.background = GradientDrawable().apply {
            setColor(0)
            setStroke(dpi(1), if (s.running) Ink.accent else Ink.ink2)
        }

        val m = s.latest
        windowInfo.text = if (s.running) "2 s window" else ""

        // Station pressure, and said so: a weather site quotes pressure reduced to
        // sea level, and 956 hPa next to its 1015 looks like a broken sensor. Notes
        // use non-breaking spaces inside quantities, so "100 000 lx" never splits
        // across a line — the same failure the wrapped JSON had.
        pressure.show(fmt(m?.pressureHpa, 1), "station, not sea level")

        light.show(fmt(m?.illuminanceLux, 0), "log · 0.1–100 000 lx",
                   m?.illuminanceLux?.let { ((log10(it.coerceAtLeast(0.1)) + 1) / 6).toFloat() })

        sound.show(fmt(m?.soundRmsDbfs, 1),
                   when {
                       m?.soundPeakDbfs != null -> "peak ${fmt(m.soundPeakDbfs, 1)} · not dB SPL"
                       s.running && !s.microphone -> "microphone not granted"
                       else -> "relative, not dB SPL"
                   },
                   m?.soundRmsDbfs?.let { ((it + 90) / 90).toFloat() })

        val moving = (m?.accelRms ?: 0.0) > 0.25 || (m?.gyroRms ?: 0.0) > 0.2
        motion.show(fmt(m?.accelRms, 2),
                    if (m?.gyroRms == null) "gravity removed"
                    else "rot ${fmt(m.gyroRms, 3)} rad/s · ${if (moving) "moving" else "still"}")

        val h = m?.headingDeg?.let(Contract::normaliseHeading)
        compass.heading = h?.toFloat()
        heading.show(fmt(h, 0), if (m?.magneticUt != null) "magnetic · ${fmt(m.magneticUt, 1)} µT" else "magnetic north")

        battery.show(fmt(m?.batteryTempC, 1), "the phone, not the room")

        val json = s.lastJson
        if (json == null) {
            wireMeta.text = ""
            wire.text = "nothing sent yet"
            wire.setTextColor(Ink.ink3)
        } else {
            val bytes = json.toByteArray(Charsets.UTF_8).size
            wireMeta.text = "${cfg?.let { "psychron/v2/${it.device}/sample · " } ?: ""}QoS 1 · mTLS · $bytes bytes"
            wire.text = highlight(json)
        }
    }

    /**
     * Colour by role, in the panel's palette: keys recede, values carry the ink, the
     * one string worth noticing — which device this is — takes the cyan, and null
     * takes the accent, because an unknown clock is the value most worth seeing.
     */
    private fun highlight(json: String): CharSequence {
        val out = SpannableStringBuilder()
        for (seg in JsonPretty.segments(json)) {
            val start = out.length
            out.append(seg.text)
            val color = when (seg.kind) {
                JsonPretty.Kind.KEY -> Ink.ink2
                JsonPretty.Kind.STRING -> Ink.cyan
                JsonPretty.Kind.NUMBER -> Ink.ink
                JsonPretty.Kind.LITERAL -> Ink.accent
                JsonPretty.Kind.PUNCTUATION -> Ink.ink3
                JsonPretty.Kind.SPACE -> null
            }
            color?.let { out.setSpan(ForegroundColorSpan(it), start, out.length, 0) }
        }
        return out
    }

    /**
     * What the node has cost in data, and what it would cost in a day at this rate.
     * The daily figure is the one that matters on a mobile plan, and it is only
     * shown once there is a minute of traffic to extrapolate from.
     */
    private fun dataUsage(s: NodeBus.State): String {
        val parts = mutableListOf("mobile ${bytes(s.meteredBytes)}", "Wi-Fi ${bytes(s.unmeteredBytes)}")
        val seconds = (SystemClock.elapsedRealtime() - s.startedElapsed) / 1000.0
        if (seconds >= 60) {
            val perDay = (s.meteredBytes + s.unmeteredBytes) / seconds * 86_400
            parts += "≈ ${bytes(perDay.toLong())}/day"
        }
        return "data · " + parts.joinToString(" · ")
    }

    private fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> String.format(Locale.ROOT, "%.0f kB", n / 1e3)
        n < 10_000_000 -> String.format(Locale.ROOT, "%.1f MB", n / 1e6)
        else -> String.format(Locale.ROOT, "%.0f MB", n / 1e6)
    }

    // ── small builders ──────────────────────────────────────────────────────

    // Android 10 only (minSdk 29): the typed insets API arrived in 11. A function of
    // its own because Kotlin no longer accepts an annotation on an assignment.
    @Suppress("DEPRECATION")
    private fun legacyInsets(insets: WindowInsets) = insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dpi(5) })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dpi(5) })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dpi(10) }
    }

    private fun mono(size: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Ink.mono
    }

    // Locale.ROOT: a Spanish phone would otherwise write 955,95 on a screen whose
    // every other number, and the JSON beneath it, uses a decimal point.
    private fun fmt(v: Double?, decimals: Int): String? =
        v?.let { String.format(Locale.ROOT, "%.${decimals}f", it) }

    companion object { private const val PERMISSIONS = 7 }
}
