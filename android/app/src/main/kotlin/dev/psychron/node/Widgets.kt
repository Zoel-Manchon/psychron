package dev.psychron.node

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The web panel's visual language, rebuilt with platform views.
 *
 * Same palette, same rules: numbers set in a monospaced face so digits never shift
 * as they change, labels engraved in small spaced capitals with a hairline under
 * them, meters whose fill is the reading and whose border is the range, and no
 * colour that means good or bad. The node and the panel should read as one system.
 */
object Ink {
    val ground = Color.parseColor("#131317")
    val panel = Color.parseColor("#1A1A1F")
    val well = Color.parseColor("#0E0E11")
    val ink = Color.parseColor("#ECE7DD")
    val ink2 = Color.parseColor("#99ECE7DD")
    val ink3 = Color.parseColor("#61ECE7DD")
    val rule = Color.parseColor("#38ECE7DD")
    val ruleFaint = Color.parseColor("#1FECE7DD")
    val accent = Color.parseColor("#D4623A")
    val cyan = Color.parseColor("#7AA8B4")

    val mono: Typeface = Typeface.MONOSPACE
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Int) = dp(v.toFloat()).toInt()

fun Context.label(text: String) = TextView(this).apply {
    this.text = text.uppercase()
    // One line, always. A label that wraps in one tile and not in its neighbour
    // pushes the numbers out of line across the whole row.
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
    setTextColor(Ink.ink2)
    typeface = Ink.mono
    letterSpacing = 0.14f
}

fun Context.hairline(color: Int = Ink.rule) = View(this).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(1))
}

/** A bar whose fill is the reading. Nothing about it changes colour with the value. */
class MeterView(context: Context) : View(context) {
    var fraction: Float? = null
        set(v) { field = v?.coerceIn(0f, 1f); invalidate() }

    private val track = Paint().apply { color = Ink.ruleFaint }
    private val fill = Paint().apply { color = Ink.accent }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(4))

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), track)
        fraction?.let { canvas.drawRect(0f, 0f, width * it, height.toFloat(), fill) }
    }
}

/**
 * Heading as a needle. A number like 243° has to be turned into a direction before
 * it means anything; a needle already is one. Magnetic north, and marked as such.
 */
class CompassView(context: Context) : View(context) {
    var heading: Float? = null
        set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ink.rule; strokeWidth = context.dp(1f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.ink3; strokeWidth = context.dp(1f) }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.accent }
    private val south = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.ink3 }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.ink }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ink.accent; typeface = Ink.mono; textSize = context.dp(8f); textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = context.dpi(64)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - context.dp(9f)
        canvas.drawCircle(cx, cy, r, ring)
        for (deg in 0 until 360 step 30) {
            val a = Math.toRadians(deg.toDouble())
            val inner = r - if (deg % 90 == 0) context.dp(5f) else context.dp(2.5f)
            canvas.drawLine(cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat(),
                            cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat(), tick)
        }
        canvas.drawText("N", cx, cy - r - context.dp(2f), letter)

        heading?.let { h ->
            canvas.save()
            canvas.rotate(h, cx, cy)
            val half = context.dp(3.2f)
            canvas.drawPath(Path().apply { moveTo(cx, cy - r + context.dp(6f)); lineTo(cx + half, cy); lineTo(cx - half, cy); close() }, north)
            canvas.drawPath(Path().apply { moveTo(cx, cy + r - context.dp(6f)); lineTo(cx + half, cy); lineTo(cx - half, cy); close() }, south)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, context.dp(2f), hub)
    }
}

/**
 * One measured quantity: what it is, its value, and what the value must not be
 * mistaken for. The note is not decoration — "station pressure" and "the phone, not
 * the room" are the difference between a reading and a misreading.
 */
class Tile(context: Context, label: String, private val unit: String, meter: Boolean = false,
           trailing: View? = null) : LinearLayout(context) {

    val value: TextView
    val note: TextView
    val meter: MeterView? = if (meter) MeterView(context) else null

    init {
        orientation = VERTICAL
        setPadding(context.dpi(12), context.dpi(11), context.dpi(12), context.dpi(12))
        background = GradientDrawable().apply {
            setColor(Ink.panel)
            setStroke(context.dpi(1), Ink.ruleFaint)
        }

        addView(context.label(label))
        addView(context.hairline(Ink.ruleFaint).also {
            (it.layoutParams as LayoutParams).apply { topMargin = context.dpi(5); bottomMargin = context.dpi(8) }
        })

        value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Ink.ink)
            typeface = Ink.monoBold
            includeFontPadding = false
            maxLines = 1
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val numberColumn = LinearLayout(context).apply { orientation = VERTICAL }
        numberColumn.addView(value)
        row.addView(numberColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { row.addView(it) }
        addView(row)

        this.meter?.let {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(4)).apply { topMargin = context.dpi(8) })
        }

        note = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(Ink.ink3)
            typeface = Ink.mono
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(6) })
    }

    /** Number and unit set as one styled string: the unit smaller and dimmer. */
    fun show(number: String?, noteText: String, fraction: Float? = null) {
        val text = android.text.SpannableStringBuilder(number ?: "—")
        if (number != null && unit == "°") {
            // An angle's degree sign belongs to the number: 265°, not 265 °.
            text.append(unit)
        } else if (number != null && unit.isNotEmpty()) {
            val start = text.length
            text.append(" ").append(unit)
            text.setSpan(android.text.style.RelativeSizeSpan(0.46f), start, text.length, 0)
            text.setSpan(android.text.style.ForegroundColorSpan(Ink.ink2), start, text.length, 0)
            text.setSpan(android.text.style.TypefaceSpan("monospace"), start, text.length, 0)
        }
        value.text = text
        note.text = noteText
        meter?.fraction = fraction
    }

    fun show(r: Readouts.Readout) = show(r.value, r.note, r.fraction)
}

/**
 * A group of tiles under a name, with the instruments behind them set small to the
 * right. Ten tiles in one unbroken grid read as a list; four named groups read as
 * what the phone is measuring about the room, about itself and about where it is.
 */
fun Context.sectionHead(title: String, sources: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
    }
    row.addView(TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Ink.ink)
        typeface = Ink.monoBold
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(TextView(context).apply {
        text = sources
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        setTextColor(Ink.ink3)
        typeface = Ink.mono
    })
    addView(row)
    addView(hairline().also { (it.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(5); bottomMargin = dpi(10) } })
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dpi(12) }
}
