package io.github.brunoccst.anothermacro

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.opencv.core.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The template capture tool: shows the grabbed game screen, drag a box around a
 * stable element (a button's text, an icon; no numbers or moving parts), name it,
 * save, and see how well it matches.
 */
class CaptureActivity : Activity() {

    private lateinit var crop: CropView
    private lateinit var name: EditText
    private lateinit var result: TextView
    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(path) ?: return finish()
        val pad = (8 * resources.displayMetrics.density).toInt()

        crop = CropView(this, bitmap)
        name = EditText(this).apply {
            hint = "name, e.g. my_button"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        result = TextView(this).apply { text = "Drag a box around a stable element (text or icon)."; setTextColor(Color.WHITE) }
        val save = Button(this).apply { text = "Save template"; setOnClickListener { save() } }
        val close = Button(this).apply { text = "Close"; setOnClickListener { finish() } }

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#222222"))
            addView(result)
            addView(name)
            addView(save)
            addView(close)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            addView(crop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
            addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })
    }

    private fun save() {
        val box = crop.selection() ?: return run { result.text = "Drag a box first." }
        val n = name.text.toString().trim()
        if (!TemplateCapture.validName(n)) {
            result.text = "Name: lowercase letters, digits and _ (e.g. my_button)."
            return
        }
        try {
            TemplateCapture.save(this, path, n, box, "")
            val m = TemplateCapture.test(this, path, n)
            result.text = if (m != null) "Saved '$n'. Match on this screen: ${"%.3f".format(m.score)} ✓\nUse it in macros as found('$n') or tap: $n"
            else "Saved '$n', but it doesn't match here; pick a more distinctive box."
            MacroLog.i("template '$n' saved (${box.width}x${box.height})")
        } catch (e: Exception) {
            result.text = "Could not save: ${e.message}"
        }
    }

    /** Shows the screen scaled to fit; dragging selects a box (in full-resolution pixels). */
    private class CropView(context: Context, private val bitmap: Bitmap) : View(context) {
        private val shown = RectF()
        private var x0 = -1f; private var y0 = -1f; private var x1 = -1f; private var y1 = -1f
        private val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW }
        private val shade = Paint().apply { color = Color.argb(110, 0, 0, 0) }

        override fun onDraw(canvas: Canvas) {
            val scale = min(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            shown.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(bitmap, null, shown, null)
            if (x0 >= 0) {
                val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                canvas.drawRect(shown.left, shown.top, shown.right, r.top, shade)
                canvas.drawRect(shown.left, r.bottom, shown.right, shown.bottom, shade)
                canvas.drawRect(shown.left, r.top, r.left, r.bottom, shade)
                canvas.drawRect(r.right, r.top, shown.right, r.bottom, shade)
                canvas.drawRect(r, frame)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x.coerceIn(shown.left, shown.right)
            val y = e.y.coerceIn(shown.top, shown.bottom)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = x; y0 = y; x1 = x; y1 = y }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { x1 = x; y1 = y }
            }
            invalidate()
            return true
        }

        fun selection(): Rect? {
            if (x0 < 0 || shown.width() <= 0) return null
            val s = bitmap.width / shown.width()
            val left = ((min(x0, x1) - shown.left) * s).toInt()
            val top = ((min(y0, y1) - shown.top) * s).toInt()
            val w = ((max(x0, x1) - min(x0, x1)) * s).toInt()
            val h = ((max(y0, y1) - min(y0, y1)) * s).toInt()
            if (w < 8 || h < 8) return null
            return Rect(left, top, min(w, bitmap.width - left), min(h, bitmap.height - top))
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
