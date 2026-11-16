package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pick one of the game's saved parties by its place in the list: Menu → Party ("Organize Party"),
 * switch with the arrows until the wanted party is in front, then Back to the field.
 *
 * The party is given only by its number (1 = the first party of the list), never by its name or
 * who is in it: every user names and fills their parties differently. The basic macro `pick-party`
 * runs this, and every dungeon macro's `party` option goes through that macro.
 *
 * The page (seen on the phone 2026-11-15): one party at a time, a big arrow at each side (left
 * ~(0.124, 0.553), right ~(0.874, 0.553); drawn, not text) and a row of dots at the bottom, one
 * per party (10), the one in front marked. The arrows wrap around (right of the last party is the
 * first). The party left in front is the active one: Back needs no confirmation.
 *
 * Which party is in front is read from the dots ([Dots]); the log names parties by number only.
 * Nothing is bought or changed besides which party is active.
 */
object Party {
    private val LEFT = Point(0.124, 0.553)
    private val RIGHT = Point(0.874, 0.553)
    /** Where the page's dots are looked for (a band across the bottom). */
    private val DOTS = Region(0.15, 0.80, 0.70, 0.19)

    /** "3" / "3.0" / "party 3" → 3; "current" / "" / anything else → null (keep the party in front). */
    fun number(want: String?): Int? =
        Regex("""^(?:party\s*)?(\d+)(?:\.0+)?$""").find(want?.trim()?.lowercase() ?: "")?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }

    /** Fewest arrow taps from party [from] to party [to] among [count] (the arrows wrap): + = right, - = left. */
    fun steps(from: Int, to: Int, count: Int): Int {
        val right = ((to - from) % count + count) % count
        return if (right <= count - right) right else right - count
    }

    /** Which party (from 1) is in front, and how many there are; null when the dots can't be read. */
    private fun inFront(ctx: Context): Pair<Int, Int>? {
        ctx.refresh()
        val bmp = ctx.colorBitmap() ?: return null
        val l = (DOTS.x * bmp.width).toInt(); val t = (DOTS.y * bmp.height).toInt()
        val w = (DOTS.w * bmp.width).toInt().coerceAtMost(bmp.width - l); val h = (DOTS.h * bmp.height).toInt().coerceAtMost(bmp.height - t)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, l, t, w, h)
        return Dots.read(px, w, h)
    }

    /** Put party [want] (from 1) in front; the stop reason the basic macro ends with. */
    fun choose(ctx: Context, want: Int): String {
        MacroLog.status("Party: picking party $want")
        openPartyPage(ctx)
        var now: Pair<Int, Int> = inFront(ctx) ?: run { ctx.wait(1.5); inFront(ctx) } ?: run {
            toField(ctx)
            ctx.stop("couldn't tell which party is in front (the dots under the party); the party in front was kept")
        }
        val count = now.second
        if (want > count) { toField(ctx); ctx.stop("there is no party $want (the game shows $count)") }
        MacroLog.i("party in front: ${now.first} of $count")
        for (attempt in 1..3) {
            if (now.first == want) break
            val n = steps(now.first, want, count)
            for (i in 1..abs(n)) { ctx.tapExact(if (n > 0) RIGHT else LEFT); ctx.wait(1.2) }
            now = inFront(ctx) ?: run { ctx.wait(1.5); inFront(ctx) } ?: run {
                toField(ctx)
                ctx.stop("couldn't tell which party is in front after switching")
            }
            MacroLog.i("party in front: ${now.first} of $count")
        }
        toField(ctx)
        if (now.first != want) ctx.stop("couldn't get party $want in front (party ${now.first} is)")
        return "party $want picked"
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

    /**
     * The page dots: small blobs that stand out from the background, in one row, evenly spaced
     * (5 to 12 of them). The marked one is the blob least like the others (colour and size).
     * Pure (ARGB pixels in, place out) so it is unit-tested without a phone.
     */
    object Dots {
        class Blob(val cx: Double, val cy: Double, val area: Int, val w: Int, val h: Int, val r: Double, val g: Double, val b: Double)

        fun read(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
            if (w < 20 || h < 5) return null
            val row = bestRow(blobs(px, w, h), w, h) ?: return null
            val i = marked(row) ?: return null
            return (i + 1) to row.size
        }

        /** Blobs of pixels clearly unlike the band's usual colour. */
        internal fun blobs(px: IntArray, w: Int, h: Int): List<Blob> {
            fun ch(p: Int, s: Int) = (p shr s) and 0xff
            val bg = intArrayOf(16, 8, 0).map { s -> px.map { ch(it, s) }.sorted()[px.size / 2] }
            val on = BooleanArray(px.size) { i ->
                val p = px[i]
                val d = abs(ch(p, 16) - bg[0]) + abs(ch(p, 8) - bg[1]) + abs(ch(p, 0) - bg[2])
                d > 90
            }
            val seen = BooleanArray(px.size)
            val out = ArrayList<Blob>()
            val stack = IntArray(px.size)
            for (start in px.indices) {
                if (!on[start] || seen[start]) continue
                var top = 0; stack[top++] = start; seen[start] = true
                var n = 0; var sx = 0L; var sy = 0L; var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
                var sr = 0L; var sg = 0L; var sb = 0L
                while (top > 0) {
                    val i = stack[--top]
                    val x = i % w; val y = i / w
                    n++; sx += x; sy += y
                    x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    sr += ch(px[i], 16); sg += ch(px[i], 8); sb += ch(px[i], 0)
                    for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                        if (j >= 0 && on[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                    }
                }
                out += Blob(sx.toDouble() / n, sy.toDouble() / n, n, x1 - x0 + 1, y1 - y0 + 1, sr.toDouble() / n, sg.toDouble() / n, sb.toDouble() / n)
            }
            return out
        }

        /** The row of dot-like blobs (left to right): same height, evenly spaced, the most of them. */
        internal fun bestRow(all: List<Blob>, w: Int, h: Int): List<Blob>? {
            val unit = w / 0.70 // the band is 0.70 of the screen's width
            val dots = all.filter { b ->
                b.w >= 2 && b.w <= unit * 0.035 && b.h <= unit * 0.035 && b.area >= 4 &&
                    b.w.toDouble() / b.h in 0.5..2.0
            }
            var best: List<Blob>? = null
            for (seed in dots) {
                val row = dots.filter { abs(it.cy - seed.cy) <= maxOf(2.0, h * 0.04) }.sortedBy { it.cx }
                if (row.size !in 5..12) continue
                val gaps = row.zipWithNext { a, b -> b.cx - a.cx }
                val mean = gaps.average()
                if (mean < 3) continue
                val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
                if (sd / mean > 0.2) continue
                if (best == null || row.size > best.size) best = row
            }
            return best
        }

        /** The index of the blob least like the rest, if it clearly stands out. */
        internal fun marked(row: List<Blob>): Int? {
            fun med(v: List<Double>) = v.sorted()[v.size / 2]
            val mr = med(row.map { it.r }); val mg = med(row.map { it.g }); val mb = med(row.map { it.b })
            val ma = med(row.map { it.area.toDouble() })
            val score = row.map { b ->
                (abs(b.r - mr) + abs(b.g - mg) + abs(b.b - mb)) / 60.0 + abs(b.area - ma) / ma
            }
            val order = score.indices.sortedByDescending { score[it] }
            val top = score[order[0]]; val next = score[order[1]]
            return if (top >= 0.5 && top >= 2 * next) order[0] else null
        }
    }
}
