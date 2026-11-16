package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pick one of the game's saved parties by its place in the list: Menu → Party ("Organize Party"),
 * switch with the arrows until the wanted party is in front. The basic macro pick-party runs it
 * between open-menu (to the menu) and back-to-field (back from the Party page).
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
    /** Where the page's dots are looked for: under the party cards, around the middle. */
    private val DOTS = Region(0.25, 0.925, 0.50, 0.065)

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

    /**
     * With the game's menu open: open its Party page and put party [want] (from 1) in front. The
     * result the basic macro pick-party ends with ("party N picked", or why not). It leaves the
     * Party page open: getting to the menu and back to the field are the basic macros open-menu
     * and back-to-field, which pick-party runs around this step.
     */
    fun choose(ctx: Context, want: Int): String {
        MacroLog.status("Party: picking party $want")
        if (!openPartyPage(ctx)) return "couldn't open the menu's Party page"
        var now: Pair<Int, Int> = inFront(ctx) ?: run { ctx.wait(1.5); inFront(ctx) }
            ?: return "couldn't tell which party is in front (the dots under the party); the party in front was kept"
        val count = now.second
        if (want > count) return "there is no party $want (the game shows $count)"
        MacroLog.i("party in front: ${now.first} of $count")
        for (attempt in 1..3) {
            if (now.first == want) break
            val n = steps(now.first, want, count)
            for (i in 1..abs(n)) { ctx.tapExact(if (n > 0) RIGHT else LEFT); ctx.wait(1.2) }
            now = inFront(ctx) ?: run { ctx.wait(1.5); inFront(ctx) }
                ?: return "couldn't tell which party is in front after switching"
            MacroLog.i("party in front: ${now.first} of $count")
        }
        return if (now.first == want) "party $want picked" else "couldn't get party $want in front (party ${now.first} is)"
    }

    /** The menu is open (open-menu): tap its Party button. */
    private fun openPartyPage(ctx: Context): Boolean {
        repeat(4) {
            ctx.refresh()
            if (ctx.find("menu_party_button") != null) {
                ctx.tap("menu_party_button"); ctx.wait(2.5)
                return true
            }
            ctx.wait(1.0)
        }
        return false
    }

    /**
     * The page dots (phone 2026-11-16, 2400x1080): a row of small grey discs centred under the
     * party, y ~0.948, ~0.017 of the width apart; the party in front is a grey RING with a white
     * middle instead of a filled disc. Read as: the filled discs (blobs clearly darker than the
     * light page) in one evenly spaced row, then every place of that row (one before and after
     * too) is looked at: dark middle = a party, light middle = the party in front.
     * Pure (ARGB pixels in, place out) so it is unit-tested without a phone.
     */
    object Dots {
        class Blob(val cx: Double, val cy: Double, val area: Int, val w: Int, val h: Int)

        private fun lum(p: Int) = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3

        /** (the party in front from 1, how many) or null when the row isn't clear. */
        fun read(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
            if (w < 20 || h < 5) return null
            val l = IntArray(px.size) { lum(px[it]) }
            val bg = l.sorted()[l.size / 2]
            val row = bestRow(blobs(l, w, h, bg), w) ?: return null
            val step = median(row.zipWithNext { a, b -> b.cx - a.cx })
            val cy = (row.sumOf { it.cy } / row.size).toInt()
            fun kind(x: Double): Int { // 1 = a disc, 2 = the ring, 0 = nothing
                val sx = Math.round(x).toInt()
                if (sx < 1 || sx >= w - 1 || cy < 1 || cy >= h - 1) return 0
                var sum = 0
                for (dy in -1..1) for (dx in -1..1) sum += l[(cy + dy) * w + sx + dx]
                val v = sum / 9
                return if (v < bg - 30) 1 else if (v >= bg + 15) 2 else 0
            }
            val first = row.first().cx
            val n = Math.round((row.last().cx - first) / step).toInt()
            val places = (-1..n + 1).map { kind(first + it * step) }.dropWhile { it == 0 }.dropLastWhile { it == 0 }
            if (0 in places || places.count { it == 2 } != 1) return null
            return (places.indexOf(2) + 1) to places.size
        }

        private fun median(v: List<Double>) = v.sorted()[v.size / 2]

        /** Blobs of pixels clearly darker than the band's usual (light) colour [bg]. */
        internal fun blobs(l: IntArray, w: Int, h: Int, bg: Int): List<Blob> {
            val on = BooleanArray(l.size) { l[it] < bg - 30 }
            val seen = BooleanArray(l.size)
            val out = ArrayList<Blob>()
            val stack = IntArray(l.size)
            for (start in l.indices) {
                if (!on[start] || seen[start]) continue
                var top = 0; stack[top++] = start; seen[start] = true
                var n = 0; var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
                while (top > 0) {
                    val i = stack[--top]
                    val x = i % w; val y = i / w
                    n++
                    x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                        if (j >= 0 && on[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                    }
                }
                out += Blob((x0 + x1) / 2.0, (y0 + y1) / 2.0, n, x1 - x0 + 1, y1 - y0 + 1)
            }
            return out
        }

        /**
         * The row of dot-like blobs (left to right): same height, evenly spaced (one gap may be
         * double: the ring isn't a disc), the most of them.
         */
        internal fun bestRow(all: List<Blob>, w: Int): List<Blob>? {
            val unit = w / DOTS.w // the screen's width in pixels
            val dots = all.filter { b ->
                b.w >= 3 && b.w <= unit * 0.02 && b.h <= unit * 0.02 && b.area >= 5 &&
                    b.w.toDouble() / b.h in 0.6..1.7
            }
            var best: List<Blob>? = null
            for (seed in dots) {
                val row = dots.filter { abs(it.cy - seed.cy) <= maxOf(2.0, seed.h * 0.4) }.sortedBy { it.cx }
                if (row.size !in 4..12) continue
                val gaps = row.zipWithNext { a, b -> b.cx - a.cx }
                val m = median(gaps)
                if (m < 3) continue
                if (gaps.any { abs(it - m) > 0.2 * m && abs(it - 2 * m) > 0.2 * m }) continue
                if (gaps.count { abs(it - 2 * m) <= 0.2 * m } > 1) continue
                if (best == null || row.size > best.size) best = row
            }
            return best
        }
    }
}
