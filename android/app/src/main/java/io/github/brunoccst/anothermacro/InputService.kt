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
        // the app was just replaced by an unattended update (Claude or the automatic check): bring it back up
        if (MacroService.capture == null && Updater.resumePending(this)) {
            main.postDelayed({
                runCatching {
                    startActivity(android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_RESUME, true))
                }.onFailure { MacroLog.e("could not reopen the app after the update", it) }
            }, 1500)
        }
    }

    /**
     * During a self-update Claude or the automatic check started (never one the user started; only for a few minutes): press Android's own
     * "Update" in the package installer and "Start now" in the screen-capture question, which this
     * app asked for itself. Nothing else is ever pressed.
     */
    private fun autoConfirm(pkg: String) {
        // only for an update nobody is watching: when the user started it, every dialog is theirs
        if (System.currentTimeMillis() > Updater.autoConfirmUntil(this)) return
        val words = when {
            "packageinstaller" in pkg -> listOf("update", "install")
            pkg == "com.android.systemui" -> listOf("start now", "start")
            // Play Protect blocking the update: only dismiss it ("Got it" keeps the block; never
            // "Install anyway", that is the user's call), so the game is usable again; the next
            // automatic check tries the install again
            pkg == "com.android.vending" -> listOf("got it")
            else -> return
        }
        fun attempt(left: Int) {
            if (clickButton(words)) {
                // dismissing Play Protect ends the update: press nothing more until the next one
                if (pkg == "com.android.vending") Updater.blocked(this, null)
                return
            }
            if (left > 0) main.postDelayed({ attempt(left - 1) }, 700)
        }
        main.postDelayed({ attempt(5) }, 500)
    }

    private fun clickButton(words: List<String>): Boolean {
        val roots = (listOfNotNull(rootInActiveWindow) + runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()))
        for (root in roots) {
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                if (label != null && label in words) {
                    var c: android.view.accessibility.AccessibilityNodeInfo? = n
                    while (c != null && !c.isClickable) c = c.parent
                    if (c != null && c.isEnabled && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        MacroLog.i("pressed '$label' (self-update)")
                        return true
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    override fun onDestroy() {
        keepScreenOn(false)
        showControls(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // which app is in front (window state changes carry the package name; other apps' content is read
        // only to press Android's own Update / Start now during a self-update, see autoConfirm)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let {
                autoConfirm(it)
                // Play Games' "welcome back" banner, Samsung's edge panel and System UI (status bar,
                // screen-capture chip, heads-up notifications) post window events from small overlay
                // views but don't take the screen: the game stays in front. Activities (Play's
                // purchase sheets) still count. Android's "isn't responding" dialog is package "android".
                if (it in OVERLAYS && event.className?.toString()?.endsWith("Activity") != true) {
                    MacroLog.i("overlay: $it (${event.className})")
                    return
                }
                if (it != packageName) {
                    if (it != foreground) MacroLog.i("in front: $it (${event.className})")
                    foreground = it
                    // the activity too: inside the game, a commercial is its own activity
                    val cls = event.className?.toString()
                    if (cls != null && cls.endsWith("Activity") && cls != foregroundActivity) {
                        if (it == ClaudeSafety.GAME && foregroundActivity != null) MacroLog.i("game activity: $cls")
                        foregroundActivity = cls
                    }
                }
            }
        }
    }

    private val OVERLAYS = setOf(
        "com.android.vending", "com.google.android.gms", "com.samsung.android.app.cocktailbarservice", "com.android.systemui",
        "com.samsung.android.game.gametools", // Samsung Game Booster's in-game panel and toasts
    )

    /** Activity class in front (last window change that named an activity). */
    @Volatile
    var foregroundActivity: String? = null
        private set

    /** Package of the app in front, as last reported by window changes. */
    @Volatile
    var foreground: String? = null
        private set

    /**
     * Check which app window really has the focus now and fix [foreground] if a window event was
     * missed or came late (e.g. the launcher's last window after the game was reopened). Overlays
     * and this app's own windows don't count.
     */
    fun recheckForeground(): String? {
        val pkg = runCatching {
            windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                ?.root?.packageName?.toString()
        }.getOrNull()
        if (pkg != null && pkg != packageName && pkg !in OVERLAYS && pkg != foreground) {
            MacroLog.i("in front (window check): $pkg, not $foreground")
            foreground = pkg
        }
        return foreground
    }

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

    /** A hold in progress (see [startHold]). Times are SystemClock.uptimeMillis(). */
    class Hold(val totalMs: Long) {
        val done = CountDownLatch(1)
        /** Ask the finger to lift where it is now (at the end of the current part). */
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var startedAt = 0L
        @Volatile var endedAt = 0L
        /** The system cancelled the gesture (the finger lifted early, without [stop]). */
        @Volatile var cancelled = false
        /** How long the finger has been down so far (or was, once [done]). */
        val heldMs: Long get() = if (startedAt == 0L) 0L else (if (endedAt == 0L) android.os.SystemClock.uptimeMillis() else endedAt) - startedAt
    }

    /**
     * Press at (x1,y1) and slide to (x2,y2) over [totalMs] like [stroke], but without blocking and
     * in parts of [partMs] chained as one continued stroke (the finger stays down between them),
     * so the caller can watch the screen and lift the finger early with [Hold.stop].
     */
    fun startHold(x1: Float, y1: Float, x2: Float, y2: Float, totalMs: Long, partMs: Long = 150): Hold {
        val h = Hold(totalMs.coerceAtLeast(1))
        val total = h.totalMs
        fun at(t: Long): Pair<Float, Float> {
            val f = (t.toFloat() / total).coerceIn(0f, 1f)
            return (x1 + (x2 - x1) * f) to (y1 + (y2 - y1) * f)
        }
        var sent = 0L
        var prev: GestureDescription.StrokeDescription? = null
        fun finish(cancelled: Boolean) {
            if (h.done.count == 0L) return
            h.cancelled = cancelled
            h.endedAt = android.os.SystemClock.uptimeMillis()
            h.done.countDown()
        }
        fun send() {
            val stopping = h.stop.get()
            val dur = if (stopping) 1L else minOf(partMs, total - sent).coerceAtLeast(1)
            val last = stopping || sent + dur >= total
            val (ax, ay) = at(sent)
            // lifting early: a last 1-pixel part where the finger is (a path needs a length to continue)
            val (bx, by) = if (stopping) (ax + (if (x2 >= x1) 1f else -1f)) to ay else at(sent + dur)
            val path = Path().apply { moveTo(ax, ay); if (ax != bx || ay != by) lineTo(bx, by) }
            val stroke = try {
                prev?.continueStroke(path, 0, dur, !last) ?: GestureDescription.StrokeDescription(path, 0, dur, !last)
            } catch (e: Exception) {
                MacroLog.e("could not continue the hold", e); null
            }
            if (stroke == null) { finish(true); return }
            run {
                prev = stroke
                val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (last) finish(false) else { sent += dur; send() }
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) { finish(true) }
                }, null)
                if (h.startedAt == 0L) h.startedAt = android.os.SystemClock.uptimeMillis()
                if (!accepted) finish(true)
            }
        }
        main.post { send() }
        return h
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
