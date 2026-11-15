package io.github.brunoccst.anothermacro

import io.github.brunoccst.anothermacro.logic.AnotherDungeon
import io.github.brunoccst.anothermacro.logic.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dungeon macros' key plan (one key, or both colours with a count each) and the party name match. */
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

    @Test fun partyNames() {
        assertTrue(Party.matches("Dungeon", "dungeon"))
        assertTrue(Party.matches("Dungeon", "Dungon"))
        assertTrue(Party.matches("Party 3", "3"))
        assertTrue(Party.matches("2 Dungeon ROnt Line", "Dungeon"))
        assertFalse(Party.matches("Dungeons", "Dungeon x"))
        assertFalse(Party.matches("Party 13", "3"))
        assertFalse(Party.matches("Farming", "Dungeon"))
    }

    @Test fun partySteps() {
        assertEquals(1, Party.steps("next"))
        assertEquals(2, Party.steps("Next 2"))
        assertEquals(-1, Party.steps("previous"))
        assertEquals(null, Party.steps("Dungeon"))
    }

    @Test fun partyByMember() {
        assertEquals("Altena", Party.member("with Altena"))
        assertEquals("Altena", Party.member(" With  Altena "))
        assertEquals(null, Party.member("General"))
    }
}
