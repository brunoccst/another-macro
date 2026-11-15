package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*
import kotlin.math.abs

/**
 * Pick one of the game's saved parties before a run: Menu → Party ("Organize Party"), then
 * switch the party shown there until it is the wanted one, then Back to the field.
 *
 * The page (seen on the phone 2026-11-15): one party at a time, its name in a box at the top
 * left ("Dungeon", "General"), the six members' names under their portraits, a big arrow at each
 * side (left ~(0.124, 0.553), right ~(0.874, 0.553); drawn, not text) and a dot per party at the
 * bottom (10 parties). The arrows wrap around (right of the last party is the first). The party
 * left shown is the active one: Back needs no confirmation.
 *
 * The wanted party is given by its name ("Dungeon"), by its number ("3" = "Party 3", if the game
 * names unrenamed parties so), by a member ("with Altena": the first party with that character),
 * or by place from the party in front: "next" / "next 2" (to the right), "previous" (to the left).
 *
 * Nothing is bought or changed besides which party is active.
 */
object Party {
    private val LEFT = Point(0.124, 0.553)
    private val RIGHT = Point(0.874, 0.553)
    /** The party's name in its box at the top left (right of the pen icon, above "Front Line"). */
    private val NAME = Region(0.195, 0.04, 0.24, 0.10)
    /** The members' names under the portraits. */
    private val MEMBERS = Region(0.15, 0.58, 0.70, 0.11)
    /** At most this many switches (the game keeps 10 parties; the arrows wrap around). */
    private const val MAX_SWITCHES = 20

    /** Does the text [line] name the wanted party [want]? */
    internal fun matches(line: String, want: String): Boolean {
        val t = Ocr.norm(line)
        val w = Ocr.norm(want)
        if (t.isEmpty() || w.isEmpty()) return false
        if (w.all { it.isDigit() }) return t == "party $w" || t == "party$w" || Regex("""^(party )?0*$w( |$)""").containsMatchIn(t)
        // the name read with a stray word of the box around it ("2 Dungeon"): the name's words in a row
        return t == w || Ocr.similarity(line, want) >= 0.85 || " $t ".contains(" $w ")
    }

    /** "with Altena" → "Altena"; else null. */
    internal fun member(want: String): String? =
        Regex("""^with\s+(.+)$""", RegexOption.IGNORE_CASE).find(want.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** "next 2" → 2, "previous" → -1, a name → null. */
    internal fun steps(want: String): Int? {
        val m = Regex("""^(next|right|previous|prev|left)(?:\s*(\d+))?$""").find(Ocr.norm(want)) ?: return null
        val n = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
        return if (m.groupValues[1] in setOf("next", "right")) n else -n
    }

    private fun name(ctx: Context) = ctx.readText(NAME).joinToString(" ") { it.text }.trim()

    /** Is the party shown the wanted one? */
    private fun isIt(ctx: Context, shown: String, want: String): Boolean {
        val who = member(want) ?: return matches(shown, want)
        return ctx.readText(MEMBERS).any { Ocr.similarity(it.text, who) >= 0.8 }
    }

    fun choose(ctx: Context, want: String) {
        MacroLog.status("Party: picking '$want'")
        openPartyPage(ctx)
        var shown = name(ctx)
        if (shown.isEmpty()) { ctx.wait(1.5); shown = name(ctx) }
        MacroLog.i("party in front: '$shown'")
        steps(want)?.let { n ->
            repeat(abs(n)) { ctx.tapExact(if (n > 0) RIGHT else LEFT); ctx.wait(1.2) }
            MacroLog.i("party: ${abs(n)} to the ${if (n > 0) "right" else "left"}: '${name(ctx)}'")
            toField(ctx)
            return
        }
        val first = shown
        var switches = 0
        while (!isIt(ctx, shown, want)) {
            if (switches >= MAX_SWITCHES) { toField(ctx); ctx.stop("no party '$want' after $switches switches") }
            ctx.tapExact(RIGHT)
            switches++
            ctx.wait(1.2)
            shown = name(ctx)
            if (first.isNotEmpty() && Ocr.norm(shown) == Ocr.norm(first)) {
                // all the way round (the arrows wrap): the party in front is left in front
                toField(ctx)
                ctx.stop("no party '$want' (went through every party back to '$first')")
            }
        }
        MacroLog.i("party '$shown' chosen ($want) after $switches switch(es)")
        toField(ctx)
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
