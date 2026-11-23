package anothermacro

import macroforge.*

import anothermacro.logic.AnotherDungeon
import anothermacro.logic.Party
import anothermacro.logic.RiftDoor
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dungeon macros' key plan (a switch per key colour, runs or all keys) and the party pick by number. */
class DungeonOptionsTest {
    private val all = Int.MAX_VALUE

    private fun plan(keys: List<String>, runs: Int) = AnotherDungeon.KeyPlan(keys, runs)

    @Test fun oneKeySwitch() {
        assertEquals(plan(listOf("green"), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "red_key" to "false", "runs" to "1")))
        assertEquals(plan(listOf("red"), 3), AnotherDungeon.keyPlan(mapOf("red_key" to "true", "runs" to "3.0")))
        assertEquals(plan(listOf("green"), 27), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to " 27 ")))
        assertEquals(plan(listOf("green"), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to "0")))
        assertEquals(plan(listOf("green"), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to "")))
        assertEquals(plan(listOf("green"), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to "-4")))
        assertEquals(plan(listOf("green"), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to "lots")))
    }

    @Test fun bothKeysRedFirstRunsPerColour() {
        assertEquals(plan(listOf("red", "green"), 7), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "red_key" to "true", "runs" to "7")))
    }

    @Test fun allKeysSwitch() {
        assertEquals(plan(listOf("red", "green"), all), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "red_key" to "true", "runs" to "4", "all_keys" to "true")))
        assertEquals(plan(listOf("red"), all), AnotherDungeon.keyPlan(mapOf("red_key" to "true", "runs" to "4", "all_keys" to "true")))
    }

    @Test fun noKeyOn() {
        assertEquals(plan(emptyList(), 1), AnotherDungeon.keyPlan(mapOf("green_key" to "false", "red_key" to "false", "runs" to "1")))
    }

    @Test fun oldSavedOptions() {
        assertEquals(plan(listOf("red"), all), AnotherDungeon.keyPlan(mapOf("key" to "red", "runs" to "all")))
        assertEquals(plan(listOf("green"), 2), AnotherDungeon.keyPlan(mapOf("runs" to "2")))
        assertEquals(plan(listOf("green"), all), AnotherDungeon.keyPlan(mapOf("green_key" to "true", "runs" to "all")))
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

    @Test fun goToDoorIsOnUnlessSwitchedOff() {
        assertEquals(true, RiftDoor.enabled(emptyMap()))   // a step or macro that doesn't pass it
        assertEquals(true, RiftDoor.enabled(mapOf("go_to_door" to "true")))
        assertEquals(true, RiftDoor.enabled(mapOf("go_to_door" to "")))
        assertEquals(false, RiftDoor.enabled(mapOf("go_to_door" to "false")))
        assertEquals(false, RiftDoor.enabled(mapOf("go_to_door" to " Off ")))
        assertEquals(false, RiftDoor.enabled(mapOf("go_to_door" to "0")))
    }

    @Test fun partyDots() {
        assertEquals(1 to 10, Party.Dots.read(dots(10, 1), 1200, 70))
        assertEquals(5 to 10, Party.Dots.read(dots(10, 5), 1200, 70))
        assertEquals(10 to 10, Party.Dots.read(dots(10, 10), 1200, 70))
        assertEquals(null, Party.Dots.read(dots(10, 0), 1200, 70))   // none marked: unreadable
    }
}
