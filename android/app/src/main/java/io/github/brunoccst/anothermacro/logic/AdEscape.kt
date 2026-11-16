package io.github.brunoccst.anothermacro.logic

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
 * Phone-tested 11-16: an AppLovin playable came straight back (and played again) after this.
 *
 * [closeGame]: the last resort. Recent apps, then swipe the game's own card away (found by the
 * game's name in the recents screen's accessibility nodes, so no other app's card is touched, and
 * nothing else there, such as "Close all", is pressed), then Home. The game process ends with the
 * commercial; the caller starts the game again. Phone-tested 11-16 on a Samsung (One UI): it
 * freed the phone from that AppLovin playable; the game then started fresh.
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

    /** The game's card in recent apps: the largest node named after the game, or null. */
    private fun gameCard(s: InputService, label: String): Rect? {
        val screen = s.resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }
        val roots = listOfNotNull(s.rootInActiveWindow) + runCatching { s.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        var best: Rect? = null
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            // named after the game itself (not an app whose name merely mentions it, such as a
            // shortcut "Another Macro (Another Eden ...)")
            val names = listOfNotNull(n.text, n.contentDescription).map { it.toString().trim().lowercase() }
            if (names.any { it.startsWith(label) }) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width().toLong() * r.height()
                // the card, not a full-screen container that merely mentions it
                if (area in 1 until screen * 9 / 10 && (best == null || area > best!!.width().toLong() * best!!.height())) best = r
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        roots.filter { it.packageName?.toString().let { p -> p != s.packageName && p != ClaudeSafety.GAME } }.forEach { visit(it, 0) }
        return best
    }

    /** Close the game from recent apps (see above). True when its card was found and swiped away. */
    fun closeGame(ctx: Context): Boolean {
        val s = InputService.instance ?: throw IllegalStateException("taps are off: turn the accessibility service on")
        val label = runCatching {
            s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(ClaudeSafety.GAME, 0)).toString()
        }.getOrDefault("Another Eden").lowercase()
        MacroLog.i("closing the game from recent apps (a commercial won't close)")
        var closed = false
        for (attempt in 1..3) {
            // once only: Recents again while it is open would close it again
            if (attempt == 1) { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); ctx.wait(2.0) }
            val card = gameCard(s, label)
            if (card == null) {
                MacroLog.i(if (closed) "the game's card is gone from recent apps" else "the game's card wasn't found in recent apps")
                break
            }
            // swipe the card up and off the screen. Still there after that: what was found may be
            // only the name above the card (a small node), so the next swipe starts lower, on the card
            val h = s.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
            val small = card.height() < h * 0.2f
            val y = card.exactCenterY() + if (attempt > 1 && small) h * 0.3f else 0f
            MacroLog.i("swiping the game's card away (attempt $attempt, card at ${card.toShortString()}, from y ${y.toInt()})")
            s.stroke(card.exactCenterX(), y, card.exactCenterX(), 1f, 250)
            closed = true
            ctx.wait(1.5)
        }
        home()
        ctx.wait(1.5)
        return closed
    }
}
