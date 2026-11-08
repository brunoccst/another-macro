package io.github.brunoccst.anothermacro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating controls over the game: the selected macro (tap it to pick another: tabs like the
 * Macros page, in a list that scrolls), and start / pause-resume / stop / restart it without
 * leaving the game, and ⌂ swaps between this app and the game: it opens this app, or, when this
 * app is already in front, brings the game back (no trip through the home screen). Drawn as an accessibility overlay (no
 * "draw over other apps" permission needed). Drag the handle to move it; tap
 * the handle to fold the buttons away. The handle's colour shows the state:
 * grey = idle, green = running, amber = paused.
 *
 * It sits on the screen edge, away from the game's buttons; macros that tap
 * near it could hit it, so move it if it's in the way.
 */
class ControlsOverlay(private val service: InputService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val buttons = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val handle = round("≡", Color.GRAY)
    private val playPause = round("▶", Color.parseColor("#2E7D32"))
    private val stop = round("■", Color.parseColor("#C62828"))
    private val restart = round("↻", Color.parseColor("#1565C0"))
    private val home = round("⌂", Color.parseColor("#455A64"))
    private val liveLog = LiveLogOverlay(service)
    private val liveListener: () -> Unit = { main.post { refresh() } }
    /** The selected macro (what ▶ starts); tap it to pick another. */
    private val selected = pill("—")
    /** The macro picker: tabs, then the tab's macros in a list that scrolls. */
    private val picker = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E61A1F26")) }
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val pickerTabs = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
    private val pickerList = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val prefs = service.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private var tab: String = prefs.getString("tab", IN_USE) ?: IN_USE
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // anchored to the right edge: the macro list grows to the left, onto the screen
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(70)
    }
    private val listener: () -> Unit = { main.post { refresh() } }
    private var wasRunning = false

    init {
        picker.addView(android.widget.HorizontalScrollView(service).apply {
            isHorizontalScrollBarEnabled = false
            addView(pickerTabs)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // the list scrolls on its own: at most about half the screen's height
        val maxH = (minOf(service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels) * 0.55).toInt()
        picker.addView(MaxHeightScrollView(service, maxH).apply { isVerticalScrollBarEnabled = true; addView(pickerList) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        buttons.addView(selected)
        buttons.addView(picker, LinearLayout.LayoutParams(dp(PICKER_W), LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        buttons.addView(playPause)
        buttons.addView(stop)
        buttons.addView(restart)
        buttons.addView(home)
        root.addView(handle)
        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.alpha = 0.85f

        playPause.setOnClickListener {
            when {
                Runner.isRunning -> service.startService(MacroService.intent(service, MacroService.ACTION_PAUSE))
                else -> start()
            }
        }
        stop.setOnClickListener { service.startService(MacroService.intent(service, MacroService.ACTION_STOP_RUN)) }
        restart.setOnClickListener { service.startService(MacroService.intent(service, MacroService.ACTION_RESTART)) }
        home.setOnClickListener { if (MainActivity.inFront) openGame() else openApp() }
        selected.setOnClickListener { togglePicker() }
        dragAndFold()
        refresh()
    }

    fun show() {
        runCatching { wm.addView(root, params) }.onFailure { MacroLog.e("could not show the controls", it) }
        Runner.listeners += listener
        LiveLog.listeners += liveListener
        liveLog.start()
    }

    fun hide() {
        Runner.listeners -= listener
        LiveLog.listeners -= liveListener
        liveLog.stop()
        runCatching { wm.removeView(root) }
    }

    private fun start() {
        val last = LastRun.load(service)
        if (last == null) {
            MacroLog.i("open the app once and run a macro; the buttons then repeat it")
            return
        }
        service.startService(LastRun.intent(service, last.first, last.second))
    }

    /** Bring the app's own screen to the front (the macro, if any, keeps running). */
    private fun openApp() {
        if (picker.visibility == View.VISIBLE) { picker.visibility = View.GONE; relayout() }
        runCatching {
            service.startActivity(Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { MacroLog.e("could not open the app", it) }
    }

    /** Back to the game from this app's screen (the macro, if any, keeps running). */
    private fun openGame() {
        if (picker.visibility == View.VISIBLE) { picker.visibility = View.GONE; relayout() }
        if (!service.launchApp(ClaudeSafety.GAME)) MacroLog.i("Another Eden isn't installed")
    }

    /** The overlay window keeps the size it was first laid out with: re-measure after the content changes. */
    private fun relayout() {
        root.post {
            runCatching {
                if (!root.isAttachedToWindow) return@runCatching
                // measure the content ourselves and size the window to it (WRAP_CONTENT doesn't grow it)
                val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                root.measure(any, any)
                params.width = root.measuredWidth
                params.height = root.measuredHeight
                wm.updateViewLayout(root, params)
            }
        }
    }

    /** Show or hide the macro picker; picking one makes it what ▶ starts. */
    private fun togglePicker() {
        if (picker.visibility == View.VISIBLE) { picker.visibility = View.GONE; relayout(); return }
        if (Runner.isRunning) { MacroLog.i("stop the macro before choosing another"); return }
        drawPicker()
        picker.visibility = View.VISIBLE
        relayout()
    }

    /** Tabs ("In use", then the groups, as on the Macros page) and the tab's macros. */
    private fun drawPicker() {
        val all = runCatching { Scenarios.all(service) }.getOrDefault(emptyList())
        val groups = Groups.sort(all)
        if (tab != IN_USE && groups.none { it.first.id == tab }) tab = IN_USE
        pickerTabs.removeAllViews()
        for ((id, title) in listOf(IN_USE to "In use") + groups.map { it.first.id to it.first.title }) {
            val sel = id == tab
            pickerTabs.addView(TextView(service).apply {
                text = title
                textSize = 11.5f
                setTextColor(if (sel) Color.parseColor("#0B1A22") else Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (sel) Color.parseColor("#4FC3F7") else Color.parseColor("#DD37474F")) }
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    tab = id
                    prefs.edit().putString("tab", id).apply()
                    drawPicker()
                    relayout()
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) }
            })
        }
        pickerList.removeAllViews()
        val sections = if (tab == IN_USE) Groups.sort(MacroChoice.used(service, all)) else groups.filter { it.first.id == tab }
        if (sections.isEmpty()) pickerList.addView(TextView(service).apply {
            text = if (all.isEmpty()) "No macros yet: open the app once with internet to download them." else "No macros here."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        val current = LastRun.load(service)?.first
        for ((group, list) in sections) {
            // the group's name above its macros (the "In use" tab mixes groups)
            if (tab == IN_USE) pickerList.addView(TextView(service).apply {
                text = " ${group.title}"
                setCompoundDrawablesWithIntrinsicBounds(service.getDrawable(group.iconRes)?.apply { setBounds(0, 0, dp(14), dp(14)) }, null, null, null)
                compoundDrawablePadding = dp(4)
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 11f
                setPadding(dp(6), dp(6), dp(6), dp(1))
            })
            for (sc in list) {
                pickerList.addView(TextView(service).apply {
                    text = (if (sc.name == current) "● " else "○ ") + sc.name
                    setTextColor(Color.WHITE)
                    textSize = 12.5f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (sc.name == current) Color.parseColor("#DD1D3A4A") else Color.parseColor("#DD37474F")) }
                    setOnClickListener {
                        // the macro's own options, as last chosen in the app
                        LastRun.save(service, sc.name, MacroParams.load(service, sc.name))
                        picker.visibility = View.GONE
                        MacroLog.i("selected macro: ${sc.name}")
                        refresh()
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, dp(2)) }
                })
            }
        }
    }

    private fun refresh() {
        // fold down to the handle while a macro runs (the buttons would cover the game, and
        // the macro sees them too); tap the handle to unfold for pause/stop
        if (Runner.isRunning != wasRunning) {
            wasRunning = Runner.isRunning
            buttons.visibility = if (wasRunning) View.GONE else View.VISIBLE
            if (wasRunning) picker.visibility = View.GONE
        }
        val color = when {
            Runner.isPaused -> Color.parseColor("#F9A825")
            Runner.isRunning -> Color.parseColor("#2E7D32")
            else -> Color.GRAY
        }
        (handle.background as GradientDrawable).setColor(color)
        playPause.text = if (Runner.isRunning && !Runner.isPaused) "❚❚" else "▶"
        selected.label("▸ " + (Runner.current?.takeIf { Runner.isRunning } ?: LastRun.load(service)?.first ?: "pick a macro"))
        relayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndFold() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = startX - dx.toInt(); params.y = startY + dy.toInt() // x counts from the right edge
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    buttons.visibility = if (buttons.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    relayout()
                }
            }
            true
        }
    }

    private fun round(label: String, color: Int) = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, dp(4), 0, dp(4)) }
        if (Build.VERSION.SDK_INT >= 21) elevation = dp(3).toFloat()
    }

    /** Set a pill's text and size it to fit (the overlay window doesn't size text views on its own). */
    private fun TextView.label(t: String) {
        text = t
        val w = (paint.measureText(t) + paddingLeft + paddingRight + dp(2)).toInt().coerceAtMost(dp(240))
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = w }
    }

    private fun pill(initial: String) = TextView(service).apply {
        text = initial
        setTextColor(Color.WHITE)
        textSize = 12f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = dp(220)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#DD37474F")) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(3), 0, dp(3)); gravity = Gravity.END }
        label(initial)
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** A ScrollView no taller than [maxH] px (the overlay window sizes itself to its content). */
    private class MaxHeightScrollView(c: Context, private val maxH: Int) : android.widget.ScrollView(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val cap = if (mode == View.MeasureSpec.UNSPECIFIED) maxH else minOf(maxH, size)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    companion object {
        private const val IN_USE = "in_use"
        private const val PICKER_W = 230
    }
}
