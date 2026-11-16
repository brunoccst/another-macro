package io.github.brunoccst.anothermacro

import io.github.brunoccst.anothermacro.logic.AnotherDungeon
import io.github.brunoccst.anothermacro.logic.Party
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dungeon macros' key plan (one key, or both colours with a count each) and the party pick by number. */
class DungeonOptionsTest {
    private val all = Int.MAX_VALUE

    @Test fun oneKey() {
        assertEquals(listOf("green" to 1), AnotherDungeon.keyPlan(mapOf("key" to "green", "runs" to "1")))
        assertEquals(listOf("red" to all), AnotherDungeon.keyPlan(mapOf("key" to "red", "runs" to "all")))
        assertEquals(listOf("green" to 3), AnotherDungeon.keyPlan(mapOf("key" to "green", "runs" to "3.0", "both_keys" to "false")))
        assertEquals(listOf("green" to 1), AnotherDungeon.keyPlan(mapOf("key" to "green", "runs" to "0")))
    }

    @Test fun bothKeysRedFirst() {
        val p = mapOf("key" to "green", "runs" to "10", "both_keys" to "true", "green_runs" to "3", "red_runs" to "2", "key_order" to "red_first")
        assertEquals(listOf("red" to 2, "green" to 3), AnotherDungeon.keyPlan(p))
        assertEquals(listOf("green" to 3, "red" to 2), AnotherDungeon.keyPlan(p + ("key_order" to "green_first")))
    }

    @Test fun bothKeysSkipsZeroAndKeepsAll() {
        assertEquals(listOf("green" to all), AnotherDungeon.keyPlan(mapOf("both_keys" to "true", "red_runs" to "0", "green_runs" to "all")))
        assertEquals(emptyList<Pair<String, Int>>(), AnotherDungeon.keyPlan(mapOf("both_keys" to "true", "red_runs" to "0", "green_runs" to "0")))
    }

    @Test fun partyNumbers() {
        assertEquals(3, Party.number("3"))
        assertEquals(10, Party.number("10.0"))
        assertEquals(1, Party.number(" Party 1 "))
        assertEquals(null, Party.number("current"))
        assertEquals(null, Party.number("Dungeon"))
        assertEquals(null, Party.number("0"))
        assertEquals(null, Party.number(null))
    }

    @Test fun partySteps() {
        assertEquals(0, Party.steps(4, 4, 10))
        assertEquals(2, Party.steps(1, 3, 10))
        assertEquals(-1, Party.steps(1, 10, 10))   // the arrows wrap: one left from the first is the last
        assertEquals(1, Party.steps(10, 1, 10))
        assertEquals(-3, Party.steps(7, 4, 10))
    }

    /** A light band like the Party page: grey discs in a row, the [marked]th (from 1) a ring with a white middle. */
    private fun dots(count: Int, marked: Int, w: Int = 1200, h: Int = 70): IntArray {
        val px = IntArray(w * h) { 0xffd2d0d8.toInt() }
        fun disc(cx: Int, cy: Int, r: Int, c: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = c
        }
        // a dark strip along the top (the bottom of the party cards)
        for (y in 0..6) for (x in 0 until w) px[y * w + x] = 0xff203050.toInt()
        val gap = 41
        val x0 = w / 2 - gap * (count - 1) / 2
        for (i in 0 until count) {
            if (i + 1 == marked) { disc(x0 + i * gap, 40, 11, 0xff8a8a90.toInt()); disc(x0 + i * gap, 40, 7, 0xfff8f8f8.toInt()) }
            else disc(x0 + i * gap, 40, 8, 0xff969498.toInt())
        }
        return px
    }

    @Test fun partyDots() {
        assertEquals(1 to 10, Party.Dots.read(dots(10, 1), 1200, 70))
        assertEquals(5 to 10, Party.Dots.read(dots(10, 5), 1200, 70))
        assertEquals(10 to 10, Party.Dots.read(dots(10, 10), 1200, 70))
        assertEquals(null, Party.Dots.read(dots(10, 0), 1200, 70))   // none marked: unreadable
    }
}
