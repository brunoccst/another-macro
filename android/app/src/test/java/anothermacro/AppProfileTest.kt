package anothermacro

import macroforge.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this app's game.yaml makes the core do. The core's own behaviour is tested in macro-forge;
 * these check this game's values: its premium currency, its run reports.
 */
class AppProfileTest {
    private fun line(text: String, x: Double, y: Double) = Ocr.Line(text, Region(x - 0.05, y - 0.02, 0.1, 0.04))

    @Test fun theGamesPremiumCurrencyIsGuarded() {
        assertTrue(ClaudeSafety.judge(listOf(line("Purchase for 500 Chronos Stones?", 0.5, 0.4), line("OK", 0.6, 0.7)), Point(0.6, 0.7)) is ClaudeSafety.Verdict.Refuse)
        assertTrue(ClaudeSafety.judge(listOf(line("Chronos Stone x50", 0.5, 0.3)), Point(0.5, 0.8)) is ClaudeSafety.Verdict.Confirm)
    }

    @Test fun dungeonReportsShowAsTheGamesTotals() {
        val report = mapOf<String, Any?>("dungeon" to "Sea of Ice", "key" to "red", "seconds" to 805, "chests" to 7, "chests_total" to 7,
            "white_key" to true, "skipped" to false)
        val fields = GameProfile.current.reports.fields
        val (totals, card) = RunStats.reportTotals(listOf(report), fields)
        assertEquals("1", totals.toMap()["Red keys used"])
        assertEquals("1", totals.toMap()["White Keys dropped"])
        assertEquals("7 of 7 (100%)", totals.toMap()["Chests opened"])
        assertEquals(null, totals.toMap()["Skipped with a Cat Express Ticket"])
        assertEquals(listOf("White Key" to 1), card)
        assertTrue(RunSummary.reportLine(report, fields), RunSummary.reportLine(report, fields).startsWith("• Sea of Ice"))
    }
}
