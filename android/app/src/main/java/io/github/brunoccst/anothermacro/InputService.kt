package io.github.brunoccst.anothermacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Touch input without root or ADB: an accessibility service dispatches gestures.
 * Taps, slides (walking: press, slide, keep holding) and Back. It also keeps the
 * screen on while a macro runs, through a 1x1 invisible accessibility overlay.
 */
class InputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var keepOnView: View? = null
    private var controls: ControlsOverlay? = null

    override fun onServiceConnected() {
        instance = this
        MacroLog.i("accessibility service connected (taps enabled)")
        if (MacroService.capture != null) showControls(true)
    }

    override fun onDestroy() {
        keepScreenOn(false)
        showControls(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // which app is in front (window state changes carry the package name; no screen content is read)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != packageName) foreground = it }
        }
    }

    /** Package of the app in front, as last reported by window changes. */
    @Volatile
    var foreground: String? = null
        private set

    /** Launch (or bring to the front) an app. A bound accessibility service may start activities from the background. */
    fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return false.also { MacroLog.e("can't launch $pkg: not installed or not visible to this app") }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        main.post { runCatching { startActivity(intent) }.onFailure { MacroLog.e("could not start $pkg", it) } }
        return true
    }
    override fun onInterrupt() {}

    /** A stroke from (x1,y1) to (x2,y2) over [durationMs]; blocks until the gesture finishes. */
    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        val done = CountDownLatch(1)
        var ok = false
        main.post {
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, null)
            if (!accepted) done.countDown()
        }
        done.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    fun tap(x: Float, y: Float) = stroke(x, y, x, y, 60)

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Keep the display awake while a macro runs (no settings change needed). */
    fun keepScreenOn(on: Boolean) {
        main.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            if (on && keepOnView == null) {
                val view = View(this)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT,
                )
                try {
                    wm.addView(view, lp)
                    keepOnView = view
                } catch (e: Exception) {
                    MacroLog.e("could not add keep-screen-on overlay", e)
                }
            } else if (!on) {
                keepOnView?.let { runCatching { wm.removeView(it) } }
                keepOnView = null
            }
        }
    }

    /** The floating start/pause/stop/restart buttons over the game. */
    fun showControls(show: Boolean) {
        main.post {
            if (show && controls == null) controls = ControlsOverlay(this).also { it.show() }
            if (!show) { controls?.hide(); controls = null }
        }
    }

    companion object {
        @Volatile
        var instance: InputService? = null
            private set
    }
}
