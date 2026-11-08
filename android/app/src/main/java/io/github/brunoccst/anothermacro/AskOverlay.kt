package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A question over the game, with one button per answer: Claude's ask_user and the
 * "Claude wants to tap 'Sell'. Allow?" confirmation. Drawn as an accessibility overlay
 * (no extra permission). Blocks the calling (agent) thread until a button is tapped,
 * the timeout passes, or [stop] turns true.
 */
object AskOverlay {
    private val main = Handler(Looper.getMainLooper())

    /** Index of the option picked, or null (timed out / stopped / no overlay possible). */
    fun ask(question: String, options: List<String>, stop: AtomicBoolean, timeoutMs: Long = 10 * 60_000L): Int? {
        val service = InputService.instance ?: return null
        val picked = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var root: View? = null
        main.post {
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = Ui.rounded(Color.parseColor("#EE161B22"), dp(16).toFloat(), Ui.ACCENT, dp(2))
                addView(TextView(service).apply {
                    text = "Claude asks"
                    setTextColor(Ui.ACCENT); textSize = 12f
                })
                addView(TextView(service).apply {
                    text = question
                    setTextColor(Color.WHITE); textSize = 15f
                    setPadding(0, dp(4), 0, dp(10))
                    maxWidth = dp(420)
                })
                val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                options.forEachIndexed { i, o ->
                    row.addView(TextView(service).apply {
                        text = o
                        setTextColor(if (i == 0) Color.parseColor("#0B1A22") else Color.WHITE)
                        textSize = 14f
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        background = Ui.rounded(if (i == 0) Ui.ACCENT else Color.parseColor("#2A313A"), dp(14).toFloat())
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
                        setOnClickListener { picked.set(i); done.countDown() }
                    })
                }
                addView(android.widget.HorizontalScrollView(service).apply { addView(row) })
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            runCatching { wm.addView(box, lp); root = box }.onFailure { MacroLog.e("could not show the question", it); done.countDown() }
        }
        val end = System.currentTimeMillis() + timeoutMs
        try {
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                if (stop.get() || System.currentTimeMillis() > end) break
            }
        } finally {
            main.post { root?.let { runCatching { wm.removeView(it) } } }
            Thread.sleep(300) // let the overlay leave the screen before the next screenshot
        }
        return picked.get().takeIf { it >= 0 }
    }
}
