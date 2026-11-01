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
 * Floating controls over the game: start / pause-resume / stop / restart the
 * last macro without leaving the game. Drawn as an accessibility overlay (no
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
    private val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val handle = round("≡", Color.GRAY)
    private val playPause = round("▶", Color.parseColor("#2E7D32"))
    private val stop = round("■", Color.parseColor("#C62828"))
    private val restart = round("↻", Color.parseColor("#1565C0"))
    private val camera = round("📷", Color.parseColor("#6A1B9A"))
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = service.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) } - dp(56)
        y = dp(70)
    }
    private val listener: () -> Unit = { main.post { refresh() } }
    private var wasRunning = false

    init {
        buttons.addView(playPause)
        buttons.addView(stop)
        buttons.addView(restart)
        buttons.addView(camera)
        root.addView(handle)
        root.addView(buttons)
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
        dragAndFold()
        refresh()
    }

    fun show() {
        runCatching { wm.addView(root, params) }.onFailure { MacroLog.e("could not show the controls", it) }
        Runner.listeners += listener
    }

    fun hide() {
        Runner.listeners -= listener
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
        }
        val color = when {
            Runner.isPaused -> Color.parseColor("#F9A825")
            Runner.isRunning -> Color.parseColor("#2E7D32")
            else -> Color.GRAY
        }
        (handle.background as GradientDrawable).setColor(color)
        playPause.text = if (Runner.isRunning && !Runner.isPaused) "❚❚" else "▶"
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
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    buttons.visibility = if (buttons.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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

    private fun dp(v: Int) = (v * density).toInt()
}
