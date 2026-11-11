package io.github.brunoccst.anothermacro

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager

/**
 * Turns the phone's screen on and keeps it on while Claude (the phone connector) or a macro
 * drives the game. With the screen off the screen capture shows only black, and taps land
 * nowhere, so every connector tool call and every macro frame checks it first.
 *
 * Two ways, no root and no settings change:
 *  - a bright screen wake lock that wakes the screen when taken (ACQUIRE_CAUSES_WAKEUP);
 *  - if the screen is still off after that (newer Android versions ignore the flag), a
 *    see-through activity that turns the screen on ([WakeActivity]) and closes itself, so
 *    the game is back in front a moment later.
 * Phones with a screen lock (PIN, pattern) stay locked: the user has to unlock them.
 *
 * Each holder ("connector", "macro") has its own lock, so one ending doesn't let the screen
 * go dark under the other.
 */
object ScreenWake {
    @Volatile private var app: android.content.Context? = null
    private val locks = HashMap<String, PowerManager.WakeLock>()
    @Volatile private var lastWakeAttempt = 0L

    fun init(c: android.content.Context) { if (app == null) app = c.applicationContext }

    private fun pm(): PowerManager? = app?.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager

    /** Whether the screen is on now; null before the app's services started. */
    fun isOn(): Boolean? = pm()?.isInteractive

    /** Keep the screen on (and turn it on) for [ms], for [holder]; taking it again restarts the time. */
    @Synchronized
    fun hold(holder: String, ms: Long) {
        val pm = pm() ?: return
        val lock = locks.getOrPut(holder) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "anothermacro:$holder",
            ).apply { setReferenceCounted(false) }
        }
        runCatching { lock.acquire(ms) }.onFailure { MacroLog.e("could not keep the screen on", it) }
    }

    @Synchronized
    fun release(holder: String) {
        locks[holder]?.let { if (it.isHeld) runCatching { it.release() } }
    }

    /**
     * Keep the screen on for [holder] and, if it is off, turn it on and wait for it (a few
     * seconds at most). True when the screen was off (so the next frame is the first one
     * after waking); the screen's state after it is [isOn].
     */
    fun ensureOn(holder: String, holdMs: Long): Boolean {
        hold(holder, holdMs)
        if (isOn() != false) return false
        return wakeNow()
    }

    /** The screen is off: turn it on. True when it was off. Blocks up to about 5 s. */
    fun wakeNow(): Boolean {
        val pm = pm() ?: return false
        if (pm.isInteractive) return false
        // a macro checks every frame: don't retry the activity many times a second
        val now = System.currentTimeMillis()
        if (now - lastWakeAttempt < 5_000L) return true
        lastWakeAttempt = now
        MacroLog.i("the screen is off: turning it on")
        @Suppress("DEPRECATION")
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "anothermacro:wake")
            .apply { setReferenceCounted(false) }.acquire(10_000L)
        if (!waitOn(pm, 1_500)) {
            WakeActivity.start()
            waitOn(pm, 3_000)
        }
        if (pm.isInteractive) {
            Thread.sleep(1_000) // the screen capture sends frames again
            MacroLog.i("the screen is on again")
        } else {
            MacroLog.e("could not turn the screen on")
        }
        return true
    }

    private fun waitOn(pm: PowerManager, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!pm.isInteractive && System.currentTimeMillis() < end) Thread.sleep(100)
        return pm.isInteractive
    }

    /**
     * What a black screen capture means, for Claude and the macro log: [screenOn] is [isOn],
     * [frameAgeMs] how long ago the capture sent its last frame (null: capture off).
     */
    fun blackReason(screenOn: Boolean?, frameAgeMs: Long?): String = when {
        frameAgeMs == null -> "the screen capture is off: open Another Macro and allow screen capture"
        screenOn == false -> "the phone's screen is off and could not be turned on (a screen lock? the user has to unlock the phone)"
        else -> "the screen capture shows only black although the screen is on (last frame ${frameAgeMs / 1000} s ago): " +
            "the capture may have stopped; turn screen capture off and on in Another Macro"
    }

    /** One line on the screen and capture, for macro_status and wake_screen. */
    fun describe(screenOn: Boolean?, frameAgeMs: Long?, black: Boolean?): String {
        val screen = when (screenOn) { true -> "on"; false -> "OFF"; null -> "unknown" }
        val cap = when {
            frameAgeMs == null -> "capture OFF"
            else -> "last capture frame ${frameAgeMs / 1000} s ago" + if (black == true) ", ALL BLACK" else ""
        }
        return "screen $screen; $cap"
    }
}

/**
 * See-through activity that turns the screen on and closes itself. In a task of its own
 * (taskAffinity in the manifest), so closing it puts the game back in front, not this app.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // a swipe-only lock screen goes away; a PIN or pattern stays (the user unlocks)
        (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        window.decorView.postDelayed({ finish() }, 1_200)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** From the accessibility service when it runs (it may start activities from the background). */
        fun start() {
            val c: android.content.Context = InputService.instance ?: return
            val i = Intent(c, WakeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { c.startActivity(i) }.onFailure { MacroLog.e("could not start the screen-on activity", it) }
            }
        }
    }
}
