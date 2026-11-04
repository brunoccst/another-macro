package io.github.brunoccst.anothermacro

import android.annotation.SuppressLint
import android.content.Context
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
 * Floating controls over the game: the selected macro (tap to pick another), and
 * start / pause-resume / stop / restart it without leaving the game. Drawn as an accessibility overlay (no
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
    private val camera = round("📷", Color.parseColor("#6A1B9A"))
    /** ⏺ record a manual run (see Recorder); while recording only ⏹ shows, under the handle. */
    private val record = round("⏺", Color.parseColor("#B71C1C"))
    private val recordStop = round("⏹", Color.parseColor("#B71C1C")).apply { visibility = View.GONE }
    private val recordListener: () -> Unit = { main.post { refresh() } }
    /** Live log on/off: a line at the bottom right saying what the macro is doing. */
    private val liveToggle = round("💬", Color.GRAY)
    private val liveLog = LiveLogOverlay(service)
    private val liveListener: () -> Unit = { main.post { refresh() } }
    /** The selected macro (what ▶ starts); tap it to pick another. */
    private val selected = pill("—")
    private val picker = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; visibility = View.GONE }
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
    private var wasRecording = false

    init {
        buttons.addView(selected)
        // (a vertical LinearLayout's default child width is MATCH_PARENT, which would clamp the names)
        buttons.addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buttons.addView(playPause)
        buttons.addView(stop)
        buttons.addView(restart)
        // 💬, 📷 and ⏺ side by side: a taller column would run off a landscape screen
        buttons.addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(liveToggle)
            addView(camera)
            addView(record)
            (liveToggle.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
            (camera.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(handle)
        root.addView(recordStop)
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
        camera.setOnClickListener { captureTemplate() }
        record.setOnClickListener { startRecording() }
        recordStop.setOnClickListener { stopRecording() }
        liveToggle.setOnClickListener { LiveLog.setEnabled(service, !LiveLog.enabled(service)) }
        selected.setOnClickListener { togglePicker() }
        dragAndFold()
        refresh()
    }

    fun show() {
        runCatching { wm.addView(root, params) }.onFailure { MacroLog.e("could not show the controls", it) }
        Runner.listeners += listener
        Recorder.listeners += recordListener
        LiveLog.listeners += liveListener
        liveLog.start()
    }

    fun hide() {
        Runner.listeners -= listener
        Recorder.listeners -= recordListener
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

    /** Show or hide the list of macros; picking one makes it what ▶ starts. */
    private fun togglePicker() {
        if (picker.visibility == View.VISIBLE) { picker.visibility = View.GONE; relayout(); return }
        if (Runner.isRunning) { MacroLog.i("stop the macro before choosing another"); return }
        picker.removeAllViews()
        val current = LastRun.load(service)?.first
        for ((group, list) in Groups.sort(runCatching { Scenarios.all(service) }.getOrDefault(emptyList()))) {
          // the group's name above its macros
          picker.addView(TextView(service).apply {
              text = " ${group.title}"
              setCompoundDrawablesWithIntrinsicBounds(service.getDrawable(group.iconRes)?.apply { setBounds(0, 0, dp(14), dp(14)) }, null, null, null)
              compoundDrawablePadding = dp(4)
              setTextColor(Color.parseColor("#B0BEC5"))
              textSize = 11f
              setPadding(dp(10), dp(6), dp(10), dp(1))
              layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }
          })
          for (sc in list) {
            picker.addView(pill((if (sc.name == current) "● " else "○ ") + sc.name).apply {
                setOnClickListener {
                    // keep the saved params when re-picking the same macro; a new one starts with its defaults
                    val params = LastRun.load(service)?.takeIf { it.first == sc.name }?.second ?: emptyMap()
                    LastRun.save(service, sc.name, params)
                    picker.visibility = View.GONE
                    MacroLog.i("selected macro: ${sc.name}")
                    refresh()
                }
            })
          }
        }
        picker.visibility = View.VISIBLE
        relayout()
    }

    /** ⏺: catch the next touches and pass them on to the game, recording each (see Recorder). */
    private fun startRecording() {
        if (Runner.isRunning) { MacroLog.i("stop the macro before recording"); return }
        if (!Recorder.start(service)) return
        // the recorder's overlay was added last and would cover these buttons: put them back on top
        runCatching { wm.removeView(root); wm.addView(root, params) }
        MacroLog.i("recording: play as usual, then ⏹")
        refresh()
    }

    /** ⏹: stop recording and ask for a name. */
    private fun stopRecording() {
        recordStop.isEnabled = false
        // leave out the moves made on these buttons (the ⏹ press itself)
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val p = android.graphics.Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }
        val pad = dp(8)
        val exclude = Region(
            (loc[0] - pad).toDouble() / p.x, (loc[1] - pad).toDouble() / p.y,
            (root.width + 2 * pad).toDouble() / p.x, (root.height + 2 * pad).toDouble() / p.y,
        )
        Recorder.stop(exclude) { dir ->
            recordStop.isEnabled = true
            if (dir == null) { MacroLog.i("recording: no moves recorded"); return@stop }
            service.startActivity(
                android.content.Intent(service, RecordingActivity::class.java)
                    .putExtra(RecordingActivity.EXTRA_DIR, dir.path)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Grab the game screen (without these buttons) and open the crop screen. */
    private fun captureTemplate() {
        if (Runner.isRunning) { MacroLog.i("stop the macro before capturing a template"); return }
        root.visibility = View.INVISIBLE
        main.postDelayed({
            Thread {
                val path = TemplateCapture.saveScreen(service)
                main.post {
                    root.visibility = View.VISIBLE
                    if (path == null) MacroLog.i("no screen capture yet (allow screen capture first)")
                    else service.startActivity(
                        android.content.Intent(service, CaptureActivity::class.java)
                            .putExtra(CaptureActivity.EXTRA_PATH, path)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }.start()
        }, 350) // let the hidden buttons leave the screen first
    }

    private fun refresh() {
        // fold down to the handle while a macro runs (the buttons would cover the game, and
        // the macro sees them too); tap the handle to unfold for pause/stop
        if (Runner.isRunning != wasRunning) {
            wasRunning = Runner.isRunning
            buttons.visibility = if (wasRunning) View.GONE else View.VISIBLE
            if (wasRunning) picker.visibility = View.GONE
        }
        // while recording only ⏹ shows: the other buttons would be recorded over
        val recording = Recorder.isRecording
        if (recording != wasRecording) {
            wasRecording = recording
            recordStop.visibility = if (recording) View.VISIBLE else View.GONE
            buttons.visibility = if (recording || Runner.isRunning) View.GONE else View.VISIBLE
            if (recording) picker.visibility = View.GONE
        }
        val color = when {
            Recorder.isRecording -> Color.parseColor("#B71C1C")
            Runner.isPaused -> Color.parseColor("#F9A825")
            Runner.isRunning -> Color.parseColor("#2E7D32")
            else -> Color.GRAY
        }
        (handle.background as GradientDrawable).setColor(color)
        (liveToggle.background as GradientDrawable).setColor(if (LiveLog.enabled(service)) Color.parseColor("#00838F") else Color.GRAY)
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
                MotionEvent.ACTION_UP -> if (!moved && !Recorder.isRecording) {
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
}
