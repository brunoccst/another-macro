package anothermacro

import anothermacro.logic.AnotherDungeon
import macroforge.Timings
import org.junit.Assert.assertEquals
import org.junit.Test

class DungeonTimeTest {
    @Test fun addsUpOwnTimeByKind() {
        fun e(ms: Long) = Timings.Entry(ms, 1)
        val times = linkedMapOf(
            "on field" to e(100_000),
            "on field › another_dungeon" to e(100_000),
            "on field › another_dungeon › map 1" to e(60_000),
            "on field › another_dungeon › map 1 › fish 1" to e(20_000),
            "on field › another_dungeon › map 1 › fish 1 › walk" to e(12_000),
            "on field › another_dungeon › map 1 › fish 1 › walk › battle" to e(9_000),
            "on field › another_dungeon › map 1 › fish 2" to e(10_000),
            "on field › another_dungeon › map 1 › fish 2 › walk" to e(6_000),
            "on field › another_dungeon › map 1 › sparkle 1" to e(25_000),
            "on field › another_dungeon › enter" to e(30_000),
        )
        val k = AnotherDungeon.timeByKind(times).toMap()
        assertEquals(30_000L, k["enter"])
        assertEquals(25_000L, k["sparkle"])
        assertEquals(12_000L, k["fish"]) // 20-12 + 10-6
        assertEquals(9_000L, k["walk"])  // 12-9 + 6
        assertEquals(9_000L, k["battle"])
        assertEquals(15_000L, k["other"]) // map 1: 60-55, the step itself: 100-60-30
    }
}
