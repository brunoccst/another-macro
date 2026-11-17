package io.github.brunoccst.anothermacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSummaryTest {
    @Test fun readsItemsFromRewardTexts() {
        assertEquals(mapOf("Chronos Stone" to 5), RunSummary.parseItems("Rewards received: 1 / Chronos Stone x5"))
        assertEquals(mapOf("Chronos Stone" to 5), RunSummary.parseItems("Rewards received 1 Chronos Stone x5 OK"))
        assertEquals(mapOf("Chronos Stones" to 10), RunSummary.parseItems("Thank you for watching. You have been rewarded 10 Chronos Stones."))
        assertEquals(mapOf("Chronos Stone" to 50, "Git" to 10000), RunSummary.parseItems("The items below have been received. Chronos Stone ×50 Git x10,000"))
        assertEquals(mapOf("Pearl" to 3), RunSummary.parseItems("Pearl x 3"))
        assertTrue(RunSummary.parseItems("Could not receive the item: you reached the limit").isEmpty())
    }

    private fun rec(macro: String, reason: String, items: Map<String, Int> = emptyMap(), reports: List<Map<String, Any?>> = emptyList(),
                    step: String? = null, screen: String? = null, routine: String? = null) =
        RunStats.Record(macro, 0L, 95_000L, reason, mapOf("battles" to 4), reports, "app", routine, items, step, screen)

    @Test fun aFinishedLoopListsEveryRunAndWhatItGot() {
        val rep = { n: Int -> mapOf<String, Any?>("dungeon" to "Sea of Ice", "seconds" to 640, "chests" to 7, "chests_total" to 7, "items" to mapOf("Pearl" to n)) }
        val o = RunSummary.of(rec("sea-of-ice", "Sea of Ice: 2 run(s) in 21m; out of keys", reports = listOf(rep(1), rep(2))))
        assertTrue(o.ok)
        assertTrue(o.title, o.title.startsWith("Sea of ice finished"))
        assertTrue(o.text, o.lines.contains("Runs: 2"))
        assertTrue(o.text, o.lines.contains("Got: Pearl ×3"))
        assertFalse(o.text, o.text.contains("Where:"))
    }

    @Test fun aStuckRunSaysWhereAndWhy() {
        val o = RunSummary.of(rec("inbox", "stuck on an unrecognised screen for 45s", step = "inbox: opening inbox item 3", screen = "inbox: inbox"))
        assertFalse(o.ok)
        assertTrue(o.title, o.title.startsWith("Inbox got stuck"))
        assertTrue(o.text, o.lines.contains("Why: stuck on an unrecognised screen for 45s"))
        assertTrue(o.text, o.text.contains("doing \"inbox: opening inbox item 3\"; last screen seen: inbox: inbox"))
    }

    @Test fun aRoutineGivesOneMessageForAllSteps() {
        val steps = listOf(
            rec("daily-ads", "all of today's commercials are done", items = mapOf("Chronos Stone" to 5), routine = "daily-routine"),
            rec("shop-ad", "error: IllegalStateException: boom", step = "shop-ad: looking for the commercial button", routine = "daily-routine"),
            rec("inbox", "inbox: 2 item(s) opened", items = mapOf("Chronos Stone" to 50), routine = "daily-routine"),
        )
        val o = RunSummary.ofRoutine("daily-routine", steps, emptyList(), "finished", 600_000L)
        assertFalse(o.ok)
        assertTrue(o.title, o.title.startsWith("Daily routine finished with problems (3 step(s)"))
        assertTrue(o.text, o.lines.contains("✓ Daily ads: all of today's commercials are done"))
        assertTrue(o.text, o.lines.contains("✕ Shop ad: error: IllegalStateException: boom"))
        assertTrue(o.text, o.text.contains("Shop ad — doing \"shop-ad: looking for the commercial button\""))
        assertTrue(o.text, o.lines.contains("Got: Chronos Stone ×55"))
    }

    @Test fun keepsItemsStepAndScreenInTheStatistics() {
        val r = rec("inbox", "stuck", items = mapOf("Git" to 3), step = "x", screen = "inbox: inbox")
        val back = RunStats.fromJson(RunStats.toJson(r))!!
        assertEquals(mapOf("Git" to 3), back.items)
        assertEquals("x", back.step)
        assertEquals("inbox: inbox", back.screen)
    }
}
