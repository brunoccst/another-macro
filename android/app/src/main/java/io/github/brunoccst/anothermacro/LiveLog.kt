package io.github.brunoccst.anothermacro

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The live log: one or two lines at the bottom right saying what the running macro is doing
 * (MacroLog.status). Only while a macro runs, and only when switched on (in the app or with
 * System → Settings). Taps go through it to the game, and the macro ignores the text
 * it covers (Context.readText), so it can sit over a button.
 */
object LiveLog {
    private const val PREFS = "live_log"

    /** Where the live log is on screen (normalized) while it is shown, else null. */
    @Volatile var area: Region? = null
        internal set

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** True while the macro reads what the live log covers: the overlay steps aside. */
    @Volatile var hidden = false
        private set

    /** Run [block] (on the macro's thread) with the live log out of the way, e.g. to read the corner it covers. */
    fun <T> without(block: () -> T): T {
        if (area == null) return block()
        hidden = true
        listeners.forEach { runCatching { it() } }
        Thread.sleep(400) // the overlay hides, and a frame without it arrives
        return try { block() } finally {
            hidden = false
            listeners.forEach { runCatching { it() } }
        }
    }

    fun enabled(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        MacroLog.i("live log ${if (on) "on" else "off"}")
        listeners.forEach { runCatching { it() } }
    }
}

class LiveLogOverlay(private val service: InputService) {
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val text = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#B0263238")) }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // never takes a touch: the macro's taps (and yours) reach the game underneath
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(6)
        y = dp(2)
    }
    private var shown = false
    private val onStatus: (String) -> Unit = { s -> main.post { text.text = s; refresh() } }
    private val onChange: () -> Unit = { main.post { refresh() } }

    fun start() {
        MacroLog.onStatus(onStatus)
        Runner.listeners += onChange
        LiveLog.listeners += onChange
        refresh()
    }

    fun stop() {
        MacroLog.offStatus(onStatus)
        Runner.listeners -= onChange
        LiveLog.listeners -= onChange
        hide()
    }

    private fun refresh() {
        val want = Runner.isRunning && LiveLog.enabled(service) && !LiveLog.hidden
        if (want && !shown) {
            val dm = service.resources.displayMetrics
            val w = (maxOf(dm.widthPixels, dm.heightPixels) * 0.38).toInt()
            params.width = w
            text.maxWidth = w
            if (text.text.isNullOrEmpty()) text.text = MacroLog.shown.ifEmpty { "starting…" }
            runCatching { wm.addView(text, params); shown = true }.onFailure { MacroLog.e("could not show the live log", it) }
            text.post { updateArea() }
        } else if (!want && shown) hide()
        else if (shown) text.post { updateArea() }
    }

    /** Remember where the text is, so the macro doesn't read it as the game's. */
    private fun updateArea() {
        if (!shown || !text.isAttachedToWindow) return
        val loc = IntArray(2)
        text.getLocationOnScreen(loc)
        val dm = service.resources.displayMetrics
        val sw = maxOf(dm.widthPixels, dm.heightPixels).toDouble()
        val sh = minOf(dm.widthPixels, dm.heightPixels).toDouble()
        // a little margin: OCR boxes can spill past the view
        LiveLog.area = Region(loc[0] / sw - 0.01, loc[1] / sh - 0.02, text.width / sw + 0.02, text.height / sh + 0.04)
    }

    private fun hide() {
        if (shown) runCatching { wm.removeView(text) }
        shown = false
        LiveLog.area = null
        text.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * density).toInt()
}
