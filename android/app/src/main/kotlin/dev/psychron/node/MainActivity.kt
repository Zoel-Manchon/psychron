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

/**
 * The node's own face: what it measures, whether it is connected, and exactly what
 * leaves the phone. The last message is shown verbatim — indented and coloured, but
 * byte for byte what went on the wire — because "only summaries leave the device"
 * is a claim, and the literal message is the evidence for it.
 */
class MainActivity : Activity() {

    private lateinit var pip: View
    private lateinit var status: TextView
    private lateinit var clockLine: TextView
    private lateinit var networkLine: TextView
    private lateinit var dataLine: TextView
    private lateinit var keyLine: TextView
    private lateinit var alertLine: TextView
    private lateinit var provisioning: TextView
    private lateinit var toggle: TextView

    private lateinit var pressure: Tile
    private lateinit var light: Tile
    private lateinit var noise: Tile
    private lateinit var motion: Tile
    private lateinit var heading: Tile
    private lateinit var battery: Tile
    private lateinit var place: Tile
    private lateinit var cell: Tile
    private lateinit var vibration: Tile
    private lateinit var queue: Tile
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
        alertLine = mono(11.5f, Ink.accent).apply { setPadding(0, 0, 0, dpi(8)) }
        column.addView(alertLine)

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pip = View(this)
        statusRow.addView(pip, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { rightMargin = dpi(8) })
        status = mono(13f, Ink.ink)
        statusRow.addView(status)
        column.addView(statusRow)
        // What was sent, replayed and dropped lives in the outbox tile now, beside
        // what is still waiting: the four counters mean something only together.
        for ((i, line) in listOf(::clockLine, ::networkLine, ::dataLine, ::keyLine).withIndex()) {
            val v = mono(10.5f, Ink.ink3).apply { setPadding(0, dpi(if (i == 0) 4 else 2), 0, 0) }
            line.set(v)
            column.addView(v)
        }

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

        // ── measurements, in the same four groups as the web panel ──────────
        pressure = Tile(this, "Pressure", "hPa")
        light = Tile(this, "Light", "lx", meter = true)
        noise = Tile(this, "Noise · A-weighted", "dBFS", meter = true)
        motion = Tile(this, "Motion", "m/s²")
        compass = CompassView(this)
        heading = Tile(this, "Heading", "°", trailing = compass)
        vibration = Tile(this, "Vibration · seismic trigger", "m/s²", meter = true)
        place = Tile(this, "Altitude · GNSS", "m")
        cell = Tile(this, "Cell · serving", "dBm")
        battery = Tile(this, "Battery", "°C")
        queue = Tile(this, "Outbox · on disk", "")

        column.addView(sectionHead("Environment", "barometer · light · microphone"))
        column.addView(pair(pressure, light))
        column.addView(wide(noise))
        column.addView(sectionHead("Motion", "accelerometer · gyroscope · compass"))
        column.addView(pair(motion, heading))
        column.addView(wide(vibration))
        column.addView(sectionHead("Position & signal", "GNSS · modem"))
        column.addView(pair(place, cell))
        column.addView(sectionHead("This phone", "battery · link"))
        column.addView(pair(battery, queue))

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
            text = "Window summaries only. Audio is reduced to a few levels in memory and never stored or sent. " +
                "Coordinates go to your own server only."
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

        handleEnrolment(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEnrolment(intent)
    }

    /**
     * infra/provision-phone.sh starts this screen with `enrol` set. The key is
     * generated off the main thread — StrongBox takes a noticeable moment — and the
     * signing request written where the script collects it.
     */
    private fun handleEnrolment(intent: Intent?) {
        if (intent?.getBooleanExtra("enrol", false) != true) return
        val device = intent.getStringExtra("device") ?: "phone-01"
        Thread({
            val result = runCatching { Provisioning.enrol(this, device) }
            runOnUiThread {
                provisioning.visibility = View.VISIBLE
                provisioning.text = result.fold(
                    { "Enrolment · key generated in ${it.name} · request ready for provision-phone.sh\n" },
                    { "Enrolment failed · ${it.javaClass.simpleName}: ${it.message}\n" },
                )
            }
        }, "psychron-enrol").start()
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

        // Asked together, once. Each is optional: a refused microphone costs the sound
        // and noise groups, a refused location the position and the band, and the
        // node runs on what it was given.
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) startNode() else requestPermissions(wanted.toTypedArray(), PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Started whatever was answered. The contract says an absent group means no
        // sample rather than a failure, so a refused permission is a smaller message.
        if (requestCode == PERMISSIONS) startNode()
    }

    private fun startNode() {
        startForegroundService(Intent(this, NodeService::class.java))
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private fun render(s: NodeBus.State) {
        val missing = Provisioning.missing(this)
        val cfg = if (missing.isEmpty()) runCatching { Provisioning.config(this) }.getOrNull() else null

        // An enrolment notice stands until a certificate for the new key is in place;
        // after that it describes a step that is over, above a node that is running.
        // A failure stands until enrolment is tried again.
        val notice = provisioning.text
        val enrolling = notice.startsWith("Enrolment failed") ||
            (notice.startsWith("Enrolment") && Provisioning.identity(this) != Provisioning.Identity.HARDWARE)
        if (!enrolling) {
            provisioning.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
            provisioning.text = "Not provisioned · missing ${missing.joinToString()}\nRun infra/provision-phone.sh with the phone connected.\n"
        }
        alertLine.visibility = if (s.alerts.isEmpty()) View.GONE else View.VISIBLE
        alertLine.text = s.alerts.values.joinToString("\n") { "▲ $it" }

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

        clockLine.text = if (s.running) "time · ${s.clock}" else ""
        clockLine.setTextColor(if (s.clock.startsWith("NTP")) Ink.ink3 else Ink.accent)

        networkLine.text = if (s.running) "network · ${s.network}${s.endpoint?.let { " → $it" } ?: ""}" +
            (if (s.batching) " · batching every 30 s" else "") else ""
        networkLine.setTextColor(if (s.network == "no network") Ink.accent else Ink.ink3)
        dataLine.text = if (s.running) dataUsage(s) else ""
        keyLine.text = if (s.running) "identity · ${s.identity}" else ""
        keyLine.setTextColor(if (s.identity.startsWith("hardware key ·")) Ink.ink3 else Ink.accent)

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

        // Station pressure, and said so: a weather site quotes pressure reduced to
        // sea level, and 956 hPa next to its 1015 looks like a broken sensor. What
        // every tile says is decided in Readouts, where it is tested.
        val m = s.latest
        val now = SystemClock.elapsedRealtime()
        pressure.show(Readouts.pressure(m))
        light.show(Readouts.light(m))
        noise.show(Readouts.noise(m, s.running, s.microphone))
        motion.show(Readouts.motion(m))
        compass.heading = Readouts.headingDegrees(m)?.toFloat()
        heading.show(Readouts.heading(m))
        vibration.show(Readouts.vibration(s, now))
        place.show(Readouts.altitude(s))
        cell.show(Readouts.cell(m))
        battery.show(Readouts.battery(m))
        queue.show(Readouts.outbox(s, System.currentTimeMillis(), now))

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
     * strings worth noticing take the cyan, and null takes the accent, because an
     * unknown clock is the value most worth seeing.
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
            parts += "≈ ${bytes(perDay.toLong())}/day"
        }
        return "data · " + parts.joinToString(" · ")
    }

    private fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> String.format(Locale.ROOT, "%.0f kB", n / 1e3)
        n < 10_000_000 -> String.format(Locale.ROOT, "%.1f MB", n / 1e6)
        else -> String.format(Locale.ROOT, "%.0f MB", n / 1e6)
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

    /** A tile across the whole width, for the two whose notes run to two lines. */
    private fun wide(tile: View) = LinearLayout(this).apply {
        addView(tile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dpi(10) }
    }

    private fun mono(size: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Ink.mono
    }

    companion object { private const val PERMISSIONS = 7 }
}
