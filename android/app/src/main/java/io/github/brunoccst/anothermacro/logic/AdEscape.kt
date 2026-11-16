package io.github.brunoccst.anothermacro.logic

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import io.github.brunoccst.anothermacro.ClaudeSafety
import io.github.brunoccst.anothermacro.Context
import io.github.brunoccst.anothermacro.FlowScenario
import io.github.brunoccst.anothermacro.InputService
import io.github.brunoccst.anothermacro.MacroLog

/**
 * A way out of a commercial that can't be closed: some playable commercials show no close X, not
 * even on their end card, and ignore Back. Bringing the game back (launch) isn't enough either,
 * because the commercial is an activity inside the game's own task and comes back with it.
 *
 * [leave]: press Home, then start the game's own activity with CLEAR_TOP + SINGLE_TOP. Android
 * then closes every activity above the game's (the commercial) and hands the running game the
 * intent, so the game carries on where it was (it isn't restarted). Nothing is tapped inside the
 * commercial (never its "Play Now" or a store page). The commercial's reward is usually lost.
 */
object AdEscape {
    fun home(): Boolean = InputService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    fun leave(ctx: Context) {
        val s = InputService.instance ?: throw IllegalStateException("taps are off: turn the accessibility service on")
        MacroLog.i("leaving a commercial that won't close: Home, then back to the game's own screen (closes the commercial)")
        home()
        ctx.wait(2.0)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val direct = Intent(Intent.ACTION_MAIN).setClassName(ClaudeSafety.GAME, FlowScenario.GAME_ACTIVITY).addFlags(flags)
        val started = runCatching { s.startActivity(direct) }.onFailure { MacroLog.e("could not open the game's own screen", it) }.isSuccess ||
            // the game's launch screen with the same flags (the game's activity may not be reachable by name)
            runCatching {
                s.startActivity(s.packageManager.getLaunchIntentForPackage(ClaudeSafety.GAME)!!.addFlags(flags))
            }.onFailure { MacroLog.e("could not open the game", it) }.isSuccess
        if (!started) ctx.launchApp(ClaudeSafety.GAME)
        ctx.wait(4.0)
    }
}
