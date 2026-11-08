package io.github.brunoccst.anothermacro

import kotlin.math.abs

/**
 * What Claude may tap. Checked in app code before every tap or swipe Claude asks for, so
 * the rules hold even when Claude misreads a screen:
 *  - only while Another Eden is in front (no other apps, settings, store or browser);
 *  - never on a purchase: Context.tap's purchase guard runs on every Claude tap, and
 *    offers to spend or buy anything are refused here too;
 *  - taps on words that can't be undone (sell, discard, release, ...) or on anything
 *    that mentions premium currency ask the user first.
 */
object ClaudeSafety {
    const val GAME = "games.wfs.anothereden"

    sealed class Verdict {
        object Ok : Verdict()
        data class Refuse(val reason: String) : Verdict()
        data class Confirm(val what: String) : Verdict()
    }

    /** Anywhere on screen: an offer to pay. Never tapped. */
    private val REFUSE_ANYWHERE = listOf("purchase", "insufficient", "not enough", "remaining purchases", "buy ")

    /** Near the tap: actions that can't be undone. The user decides. */
    private val RISKY_NEAR = listOf(
        "sell", "discard", "release", "delete", "dismantle", "exchange", "trade", "reset", "transfer",
        "convert", "spend", "encounter", "skip story", "data link", "unlink", "log out", "logout", "uninstall",
    )

    /** Anywhere on screen: premium currency. Taps there need the user's OK. */
    private val PREMIUM_ANYWHERE = listOf("chronos stone", "tsubura", "paid stone")

    fun gameInFront(ctx: Context): Boolean {
        val fg = ctx.foreground
        if (fg != null) return fg == GAME
        val f = ctx.frame ?: return false
        return !f.portraitAppInFront
    }

    /** Check a tap (or the start of a swipe) at [p], with the text on screen now. */
    fun check(ctx: Context, p: Point): Verdict {
        if (!gameInFront(ctx)) return Verdict.Refuse("Another Eden is not in front (${ctx.foreground ?: "unknown app"}); use launch_game first")
        return judge(ctx.readText(), p)
    }

    /** The word checks alone: [lines] is the text on screen, [p] where Claude wants to tap. */
    fun judge(lines: List<Ocr.Line>, p: Point): Verdict {
        val all = lines.joinToString(" | ") { Ocr.norm(it.text) } + " "
        REFUSE_ANYWHERE.firstOrNull { it in all }?.let {
            return Verdict.Refuse("the screen offers a purchase ('${it.trim()}'); this app never buys anything. Close it with its X/Cancel or Back")
        }
        val near = lines.filter { l ->
            val b = l.box
            p.x in (b.x - 0.04)..(b.x + b.w + 0.04) && p.y in (b.y - 0.04)..(b.y + b.h + 0.04) ||
                abs(l.center.x - p.x) < 0.06 && abs(l.center.y - p.y) < 0.05
        }
        for (l in near) {
            val n = " " + Ocr.norm(l.text) + " "
            RISKY_NEAR.firstOrNull { " $it " in n || n.contains(" $it") }?.let { return Verdict.Confirm(l.text.trim()) }
        }
        PREMIUM_ANYWHERE.firstOrNull { it in all }?.let { return Verdict.Confirm("a screen that mentions $it") }
        return Verdict.Ok
    }
}
