package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*
import kotlin.math.abs

/**
 * Pick one of the game's saved parties before a run: Menu → Party, then switch the party shown
 * there until its name is the wanted one, then Back to the field.
 *
 * The wanted party is given by its name as the game shows it ("Dungeon"), by its number
 * ("3" = "Party 3", the game's name for a party nobody renamed), or by where it sits from the
 * party in front: "next" / "next 2" (to the right), "previous" (to the left). The Party page shows one party
 * at a time with its name near the top; the switch goes by the arrow next to the name (read by
 * OCR as "<" / ">" or similar), else by a swipe across the party. The page is read again after
 * every switch, and the search stops when the first party's name comes back (all seen).
 *
 * Nothing is bought or changed besides which party is active.
 */
object Party {
    /** The top part of the Party page, where the party's name shows. */
    private val TOP = Region(0.0, 0.0, 1.0, 0.3)
    /** At most this many switches (the game keeps a few dozen parties at most). */
    private const val MAX_SWITCHES = 40
    private val NEXT_ARROWS = setOf(">", "›", "▶", "▷", "»", ")")
    private val PREV_ARROWS = setOf("<", "‹", "◀", "◁", "«", "(")

    /** Does the text [line] name the wanted party [want]? */
    internal fun matches(line: String, want: String): Boolean {
        val t = Ocr.norm(line)
        val w = Ocr.norm(want)
        if (t.isEmpty() || w.isEmpty()) return false
        if (w.all { it.isDigit() }) return t == "party $w" || t == "party$w" || Regex("""^(party )?0*$w( |$)""").containsMatchIn(t)
        return t == w || Ocr.similarity(line, want) >= 0.85
    }

    /** The party name line on the page: the one matching [want], else the likeliest name ("Party 2", a short line at the top). */
    private fun name(lines: List<Ocr.Line>, want: String): Ocr.Line? =
        lines.firstOrNull { matches(it.text, want) }
            ?: lines.filter { it.center.y < 0.3 && Ocr.norm(it.text).startsWith("party ") }.minByOrNull { it.center.y }

    /** "next 2" → 2, "previous" → -1, a name → null. */
    internal fun steps(want: String): Int? {
        val m = Regex("""^(next|right|previous|prev|left)(?:\s*(\d+))?$""").find(Ocr.norm(want)) ?: return null
        val n = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
        return if (m.groupValues[1] in setOf("next", "right")) n else -n
    }

    fun choose(ctx: Context, want: String) {
        MacroLog.status("Party: picking '$want'")
        openPartyPage(ctx)
        var lines = ctx.readText(TOP)
        steps(want)?.let { n ->
            MacroLog.i("party page: ${lines.joinToString(" | ") { it.text }.take(200)}")
            repeat(abs(n)) { step(ctx, lines, n > 0); ctx.wait(1.2); lines = ctx.readText(TOP) }
            MacroLog.i("party: ${abs(n)} to the ${if (n > 0) "right" else "left"}: ${lines.joinToString(" | ") { it.text }.take(120)}")
            toField(ctx)
            return
        }
        val first = name(lines, want)?.text
        MacroLog.i("party page: ${lines.joinToString(" | ") { it.text }.take(200)}")
        var switches = 0
        while (lines.none { matches(it.text, want) }) {
            if (switches >= MAX_SWITCHES) { toField(ctx); ctx.stop("no party named '$want' after $switches switches (start with the party in front, or check its name)") }
            step(ctx, lines, true)
            switches++
            ctx.wait(1.2)
            lines = ctx.readText(TOP)
            val now = name(lines, want)?.text
            if (switches > 1 && first != null && now != null && Ocr.norm(now) == Ocr.norm(first)) {
                toField(ctx)
                ctx.stop("no party named '$want' (went through every party back to '$first')")
            }
        }
        MacroLog.i("party '$want' chosen after $switches switch(es)")
        toField(ctx)
    }

    /** One party to the right (or left): the arrow on that side if OCR sees one, else a swipe across the page. */
    private fun step(ctx: Context, lines: List<Ocr.Line>, right: Boolean) {
        val arrow = if (right) lines.filter { it.text.trim() in NEXT_ARROWS }.maxByOrNull { it.center.x }
            else lines.filter { it.text.trim() in PREV_ARROWS }.minByOrNull { it.center.x }
        if (arrow != null) { ctx.tapExact(arrow.center); return }
        if (right) ctx.drag(Point(0.7, 0.5), Point(0.3, 0.5), 300) else ctx.drag(Point(0.3, 0.5), Point(0.7, 0.5), 300)
    }

    private fun openPartyPage(ctx: Context) {
        repeat(4) {
            ctx.refresh()
            if (ctx.find("menu_party_button") != null) {
                ctx.tap("menu_party_button"); ctx.wait(2.5)
                return
            }
            if (ctx.find("field_menu_button") != null) { ctx.tap("field_menu_button"); ctx.wait(2.0) } else ctx.wait(1.0)
        }
        ctx.stop("couldn't open the menu's Party page")
    }

    /** Back until the field shows (never Back on the field: that asks to close the game). */
    private fun toField(ctx: Context) {
        repeat(8) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) return
            ctx.back(); ctx.wait(1.5)
        }
        ctx.refresh()
        if (ctx.find("field_menu_button") == null) ctx.stop("couldn't get back to the field from the Party page")
    }
}
