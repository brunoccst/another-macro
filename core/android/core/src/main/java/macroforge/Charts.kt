package macroforge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The Statistics page's graphs, drawn in code like the rest of the app. */
object Charts {
    /** One bar: its label (on the axis and when touched), value, the text shown when touched, and its colour. */
    data class Bar(val label: String, val value: Double, val detail: String, val color: Int = Ui.ACCENT)

    /**
     * A column chart: thin bars with rounded tops on a baseline, the top value as a faint grid
     * line, a few labels under the axis. Touching a bar shows its [Bar.detail] above the chart.
     */
    @SuppressLint("ViewConstructor")
    class Columns(c: Context, private val bars: List<Bar>, private val emptyText: String = "No runs yet") : View(c) {
        private val d = c.resources.displayMetrics.density
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = d }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 10.5f * d }
        private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = 12.5f * d }
        private var selected = -1
        private val max = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0

        init {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (170 * d).toInt())
            contentDescription = bars.joinToString("; ") { it.detail }
        }

        private val top get() = 30 * d
        private val bottom get() = height - 20 * d
        private val left get() = 4 * d
        private val right get() = width - 4 * d

        private fun slot() = (right - left) / bars.size.coerceAtLeast(1)

        override fun onDraw(canvas: Canvas) {
            if (bars.isEmpty() || bars.all { it.value <= 0 }) {
                canvas.drawText(emptyText, left, (top + bottom) / 2, label)
                canvas.drawLine(left, bottom, right, bottom, axis)
                return
            }
            // the top value as a faint grid line, labelled at the right
            canvas.drawLine(left, top, right, top, grid)
            val maxText = fmt(max)
            canvas.drawText(maxText, right - label.measureText(maxText), top - 4 * d, label)
            val w = slot()
            val gap = 2 * d
            val bw = (w - gap).coerceIn(2 * d, 22 * d)
            val r = (4 * d).coerceAtMost(bw / 2)
            for ((i, b) in bars.withIndex()) {
                if (b.value <= 0) continue
                val cx = left + w * i + w / 2
                val h = ((bottom - top) * (b.value / max)).toFloat().coerceAtLeast(2 * d)
                barPaint.color = b.color
                barPaint.alpha = if (selected == -1 || selected == i) 255 else 110
                val rect = RectF(cx - bw / 2, bottom - h, cx + bw / 2, bottom)
                // rounded at the top, square on the baseline
                val path = Path().apply { addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW) }
                canvas.drawPath(path, barPaint)
            }
            canvas.drawLine(left, bottom, right, bottom, axis)
            // labels: as many as fit with clear space between them, counted back from the last
            // bar (today) so it is always labelled and the spacing stays even
            val widest = bars.maxOf { label.measureText(it.label) }
            val every = kotlin.math.ceil((widest + 12 * d) / w).toInt().coerceAtLeast(1)
            var lastStart = Float.MAX_VALUE
            for (i in bars.lastIndex downTo 0 step every) {
                val b = bars[i]
                val tw = label.measureText(b.label)
                val x = (left + w * i + w / 2 - tw / 2).coerceIn(left, right - tw)
                if (x + tw + 6 * d > lastStart) continue  // never let two labels touch
                canvas.drawText(b.label, x, height - 5 * d, label)
                lastStart = x
            }
            val text = if (selected in bars.indices) bars[selected].detail else "Touch a bar for details"
            focus.color = if (selected in bars.indices) Ui.TEXT else Ui.MUTED
            canvas.drawText(text, left, 14 * d, focus)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bars.isEmpty()) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(e.action == MotionEvent.ACTION_MOVE)
                    val i = ((e.x - left) / slot()).toInt().coerceIn(0, bars.lastIndex)
                    if (i != selected) { selected = i; invalidate() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
    }

    fun fmt(v: Double) = if (v >= 10 || v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    /**
     * Rows of horizontal bars: a name, a bar as long as its share of the largest value, and the
     * value as text (identity and amount never rely on colour alone).
     */
    fun rows(c: Context, items: List<Triple<String, Double, String>>, color: (Int) -> Int = { Ui.ACCENT }): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val max = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
        for ((i, item) in items.withIndex()) {
            val (name, value, text) = item
            box.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4))
                addView(Ui.text(c, name, 13f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val share = (value / max).toFloat().coerceIn(0.02f, 1f)
                    addView(View(c).apply { background = Ui.rounded(color(i), Ui.dp(c, 3).toFloat()) },
                        LinearLayout.LayoutParams(0, Ui.dp(c, 10), share))
                    if (share < 1f) addView(View(c), LinearLayout.LayoutParams(0, 1, 1f - share))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f).apply { leftMargin = Ui.dp(c, 8) })
                addView(Ui.text(c, text, 12.5f, Ui.MUTED).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f))
            })
        }
        return box
    }

    /** A small legend: a coloured square and its name, for each entry. */
    fun legend(c: Context, entries: List<Pair<String, Int>>): View = android.widget.HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(c, 6), 0, 0)
            for ((name, color) in entries) {
                addView(View(c).apply { background = Ui.rounded(color, Ui.dp(c, 2).toFloat()) },
                    LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)).apply { rightMargin = Ui.dp(c, 6); gravity = Gravity.CENTER_VERTICAL })
                addView(Ui.text(c, name, 12f, Ui.MUTED), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.dp(c, 14) })
            }
        })
    }

    /** A grid of figures: a big number and its name under it, two per row. */
    fun tiles(c: Context, figures: List<Pair<String, String>>): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for (pair in figures.chunked(2)) {
            box.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((name, value) in pair) addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6))
                    addView(Ui.text(c, value, 20f, bold = true))
                    addView(Ui.text(c, name, 12f, Ui.MUTED))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (pair.size == 1) addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            })
        }
        return box
    }

    /** "key  value" lines, for counters and rewards. */
    fun table(c: Context, rows: List<Pair<String, String>>): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        for ((k, v) in rows) addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3))
            addView(Ui.text(c, k, 13.5f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(c).apply { text = v; textSize = 13.5f; setTextColor(Ui.TEXT); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
        })
    }
}
