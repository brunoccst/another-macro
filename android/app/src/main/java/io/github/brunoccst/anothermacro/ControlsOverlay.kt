package io.github.brunoccst.anothermacro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 * Floating controls over the game (round chrome-and-navy icons from res/drawable-nodpi/overlay_*):
 * the selected macro (tap it to pick another: tabs like the
 * Macros page, in a list that scrolls), and start / pause-resume / stop / restart it without
 * leaving the game, and ⌂ swaps between this app and the game: it opens this app, or, when this
 * app is already in front, brings the game back (no trip through the home screen). Drawn as an accessibility overlay (no
 * "draw over other apps" permission needed). Drag the handle to move it; tap
 * the handle (the round "AM" badge) to fold the buttons away. A ring around the handle shows
 * the state: none = idle, a bright chrome-blue arc spinning round the badge = running, a steady amber ring = paused.
 *
 * It sits on the screen edge, away from the game's buttons; macros that tap
 * near it could hit it, so move it if it's in the way.
 */
class ControlsOverlay(private val service: InputService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    /**
     * Two windows: the handle's never changes size, so the badge stays still; the buttons'
     * window sits right under it and is only on screen while unfolded (its size changes with
     * the macro's name and the picker, and the buttons slide down out of the badge).
     */
    private val handleRoot = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val buttons = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    /** The state ring drawn around the handle's badge (spins while a macro runs). */
    private val ring = SpinnerRing(dp(RING).toFloat())
    private val handle = round(R.drawable.overlay_bubble, "Another Macro controls").apply {
        background = android.graphics.drawable.LayerDrawable(arrayOf(ring, service.getDrawable(R.drawable.overlay_bubble)))
            .apply { setLayerInset(1, dp(RING), dp(RING), dp(RING), dp(RING)) }
    }
    private val playIcon = service.getDrawable(R.drawable.overlay_play)
    private val pauseIcon = service.getDrawable(R.drawable.overlay_pause)
    private val playPause = round(R.drawable.overlay_play, "Start")
    private val stop = round(R.drawable.overlay_stop, "Stop")
    private val restart = round(R.drawable.overlay_restart, "Restart")
    private val home = round(R.drawable.overlay_home, "Switch between the app and the game")
    private val liveLog = LiveLogOverlay(service)
    private val liveListener: () -> Unit = { main.post { refresh() } }
    /** The selected macro (what ▶ starts); tap it to pick another. */
    private val selected = pill("—")
    /** The macro picker: tabs, then the tab's macros in a list that scrolls. */
    private val picker = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Ui.OVERLAY_BG); setStroke(dp(1), Ui.LINE) }
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val pickerTabs = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
    private val pickerList = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val prefs = service.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private var tab: String = prefs.getString("tab", IN_USE) ?: IN_USE
    private fun windowParams() = WindowManager.LayoutParams(
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
        windowAnimations = 0
    }
    /** The handle's window. */
    private val handleParams = windowParams()
    /** The buttons' window, right under the handle's. */
    private val params = windowParams()
    /** Folded = only the handle shows (the buttons' window is off screen). */
    private var isFolded = false
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
        handleRoot.addView(handle)
        // the handle's window is sized once, to the handle
        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        handleRoot.measure(any, any)
        handleParams.width = handleRoot.measuredWidth
        handleParams.height = handleRoot.measuredHeight
        handleRoot.alpha = 0.85f
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
        runCatching { wm.addView(handleRoot, handleParams) }.onFailure { MacroLog.e("could not show the controls", it) }
        if (!isFolded) placePanel()
        Runner.listeners += listener
        LiveLog.listeners += liveListener
        liveLog.start()
    }

    fun hide() {
        Runner.listeners -= listener
        LiveLog.listeners -= liveListener
        liveLog.stop()
        buttons.animate().cancel()
        runCatching { if (root.parent != null) wm.removeView(root) }
        runCatching { wm.removeView(handleRoot) }
    }

    /** Whether the buttons are folded away (only the round badge handle shows). Main thread. */
    val folded: Boolean get() = isFolded

    /**
     * Fold the buttons up into the handle, or unfold them: for Claude (the connector's
     * floating_controls tool, and before a tap the unfolded window would swallow) at once, and
     * for a tap on the handle with the slide. Main thread.
     */
    fun setFolded(fold: Boolean, animate: Boolean = false) {
        if (fold == isFolded) return
        isFolded = fold
        buttons.animate().cancel()
        if (!fold) {
            placePanel(slide = animate)
            return
        }
        val gone = {
            picker.visibility = View.GONE
            runCatching { if (root.parent != null) wm.removeView(root) }
            buttons.translationY = 0f; buttons.alpha = 1f
        }
        if (animate && root.parent != null && root.height > 0) {
            buttons.animate().translationY(-root.height.toFloat()).alpha(0f).setDuration(SLIDE_MS)
                .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { if (isFolded) gone() }.start()
        } else gone()
    }

    /** The windows' area on the screen in screen pixels (taps there reach these buttons, not the game). Main thread. */
    fun area(): android.graphics.Rect? {
        fun rect(v: View): android.graphics.Rect? {
            if (!v.isAttachedToWindow || v.width == 0) return null
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            return android.graphics.Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
        }
        val r = rect(handleRoot) ?: return null
        if (!isFolded) rect(root)?.let { r.union(it) }
        return r
    }

    /** Size the buttons' window to its content and put it right under the handle; [slide] = slide the buttons down out of the badge. */
    private fun placePanel(slide: Boolean = false) {
        runCatching {
            if (handleRoot.parent == null) return@runCatching // not on screen
            val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            root.measure(any, any)
            params.width = root.measuredWidth
            params.height = root.measuredHeight
            params.x = handleParams.x
            params.y = handleParams.y + handleParams.height
            if (slide) { buttons.alpha = 0f; buttons.translationY = -params.height.toFloat() }
            if (root.parent != null) wm.updateViewLayout(root, params) else wm.addView(root, params)
            if (slide) {
                // start once the window has its new size, so nothing is drawn at the old one
                root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (root.width != params.width || root.height != params.height) return true
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        if (!isFolded) buttons.animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                        return true
                    }
                })
            }
        }.onFailure { MacroLog.e("could not show the buttons", it) }
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
        // measure the content ourselves and size the window to it (WRAP_CONTENT doesn't grow it)
        root.post { if (!isFolded && root.parent != null) placePanel() }
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
                setTextColor(if (sel) Ui.ON_ACCENT else Ui.TEXT)
                background = if (sel) Ui.chrome(dp(12).toFloat()) else GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Ui.OVERLAY_ITEM) }
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
            setTextColor(Ui.MUTED)
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
                setTextColor(Ui.MUTED)
                textSize = 11f
                setPadding(dp(6), dp(6), dp(6), dp(1))
            })
            for (sc in list) {
                pickerList.addView(TextView(service).apply {
                    val cur = sc.name == current
                    text = (if (cur) "● " else "○ ") + sc.name
                    setTextColor(if (cur) Ui.ACCENT else Ui.TEXT)
                    textSize = 12.5f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (cur) Ui.ACCENT_DIM else Ui.OVERLAY_ITEM); if (cur) setStroke(dp(1), Ui.ACCENT) }
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
            setFolded(wasRunning, animate = true)
        }
        when {
            Runner.isPaused -> ring.set(SpinnerRing.Mode.STILL, PAUSED)
            Runner.isRunning -> ring.set(SpinnerRing.Mode.SPIN, RUNNING)
            else -> ring.set(SpinnerRing.Mode.OFF, Color.TRANSPARENT)
        }
        val pause = Runner.isRunning && !Runner.isPaused
        playPause.background = if (pause) pauseIcon else playIcon
        playPause.contentDescription = if (pause) "Pause" else if (Runner.isPaused) "Resume" else "Start"
        selected.label("▸ " + (Runner.current?.takeIf { Runner.isRunning } ?: LastRun.load(service)?.first ?: "pick a macro"))
        relayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndFold() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = handleParams.x; startY = handleParams.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        handleParams.x = startX - dx.toInt(); handleParams.y = startY + dy.toInt() // x counts from the right edge
                        runCatching { wm.updateViewLayout(handleRoot, handleParams) }
                        if (!isFolded && root.parent != null) {
                            params.x = handleParams.x; params.y = handleParams.y + handleParams.height
                            runCatching { wm.updateViewLayout(root, params) }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) setFolded(!isFolded, animate = true)
            }
            true
        }
    }

    /** A round icon button (the art is a disc on a transparent square, so the shadow is made round too). */
    private fun round(icon: Int, description: String) = View(service).apply {
        background = service.getDrawable(icon)
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, dp(4), 0, dp(4)) }
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        elevation = dp(3).toFloat()
    }

    /** Set a pill's text and size it to fit (the overlay window doesn't size text views on its own). */
    private fun TextView.label(t: String) {
        text = t
        val w = (paint.measureText(t) + paddingLeft + paddingRight + dp(2)).toInt().coerceAtMost(dp(240))
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = w }
    }

    private fun pill(initial: String) = TextView(service).apply {
        text = initial
        // styled like the round buttons: navy serif lettering on a misty off-white face,
        // inside a navy ring and a chrome bezel
        setTextColor(Ui.NAVY)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        textSize = 13f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = dp(220)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(8), dp(13), dp(8))
        background = Ui.bezel(service, 17)
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
        /** Width of the state ring around the handle, dp. */
        private const val RING = 3
        /** How long the buttons take to slide out of / back into the badge, ms. */
        private const val SLIDE_MS = 200L
        /** State ring colours. */
        private val RUNNING = Color.parseColor("#9CC2FF") // bright chrome blue
        private val PAUSED = Ui.WARN
    }
}
