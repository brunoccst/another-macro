package anothermacro

import macroforge.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RunStatsTest {
    private fun rec(macro: String, start: Long, mins: Int, reason: String, counts: Map<String, Int> = emptyMap(), reports: List<Map<String, Any?>> = emptyList()) =
        RunStats.Record(macro, start, start + mins * 60_000L, reason, counts, reports)

    @Test fun classifiesStopReasons() {
        assertEquals(RunStats.Outcome.STOPPED, RunStats.classify("stop requested"))
        assertEquals(RunStats.Outcome.ERROR, RunStats.classify("error: IllegalStateException: boom"))
        assertEquals(RunStats.Outcome.SAFETY, RunStats.classify("safety stop: the shop or a purchase popup was on screen (no tap made there)"))
        assertEquals(RunStats.Outcome.STUCK, RunStats.classify("stuck on an unrecognised screen for 45s"))
        assertEquals(RunStats.Outcome.DONE, RunStats.classify("Sea of Ice done — 13m 25s; chests 7/7"))
        assertEquals(RunStats.Outcome.DONE, RunStats.classify("finished"))
    }

    @Test fun groupsReasonsWithoutNumbers() {
        assertEquals("Sea of Ice done", RunStats.reasonGroup("Sea of Ice done — 13m 25s; chests 7/7, battles 12"))
        assertEquals("Sea of Ice: n run(s) in nm", RunStats.reasonGroup("Sea of Ice: 3 run(s) in 41m; out of keys"))
    }

    @Test fun storesClearsAndSummarizes() {
        val dir = Files.createTempDirectory("stats").toFile()
        RunStats.useDir(dir)
        RunStats.clear()
        val report = mapOf<String, Any?>("dungeon" to "Sea of Ice", "key" to "red", "seconds" to 805, "chests" to 7, "chests_total" to 7,
            "white_key" to true, "skipped" to false, "items" to mapOf("Pearl" to 2), "rewards" to mapOf("Pearl" to 3, "Git" to 500))
        RunStats.record(rec("sea-of-ice", 1_000_000L, 13, "Sea of Ice done — 13m", mapOf("battles" to 12, "chests" to 7), listOf(report)))
        RunStats.record(rec("sea-of-ice", 2_000_000L, 5, "stop requested", mapOf("battles" to 3)))
        RunStats.record(rec("inbox", 3_000_000L, 1, "finished"))
        assertEquals(3, RunStats.all().size)

        val s = RunStats.summarize(RunStats.all().filter { it.macro == "sea-of-ice" })
        assertEquals(2, s.runs)
        assertEquals(18 * 60_000L, s.totalMs)
        assertEquals(15, s.counts["battles"])
        assertEquals(1, s.outcomes[RunStats.Outcome.DONE])
        assertEquals(1, s.outcomes[RunStats.Outcome.STOPPED])
        assertEquals(1, s.reports)
        val totals = s.reportTotals.toMap()
        assertEquals("1", totals["Red keys used"])
        assertEquals("1", totals["White Keys dropped"])
        assertEquals("7 of 7 (100%)", totals["Chests opened"])
        assertEquals(null, totals["Skipped with a Cat Express Ticket"])
        assertEquals(listOf("White Key" to 1), s.cardTotals)
        assertEquals("Git" to 500, s.items.first())
        assertEquals(5, s.items.toMap()["Pearl"])

        RunStats.clear("sea-of-ice")
        assertEquals(listOf("inbox"), RunStats.all().map { it.macro })
        RunStats.clear()
        assertTrue(RunStats.all().isEmpty())
    }

    @Test fun roundTripsJson() {
        val r = RunStats.Record("dogma-tower", 5L, 65_005L, "done", mapOf("chests" to 7), listOf(mapOf("dungeon" to "Dogma Tower", "white_key" to false)), "connector", "daily-routine")
        assertEquals(r, RunStats.fromJson(RunStats.toJson(r)))
    }

    @Test fun readsOnlyNewReports() {
        val dir = Files.createTempDirectory("files").toFile()
        val log = java.io.File(dir, "runs.log")
        log.writeText("{\"dungeon\":\"old\"}\n")
        val mark = RunStats.reportsMark(dir)
        log.appendText("{\"dungeon\":\"Sea of Ice\",\"chests\":7}\n")
        val got = RunStats.reportsSince(dir, mark)
        assertEquals(1, got.size)
        assertEquals("Sea of Ice", got[0]["dungeon"])
    }

    @Test fun countsRunsPerDay() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val now = 20 * day + 3_600_000L
        val runs = listOf(rec("a", 20 * day + 60_000, 10, "finished"), rec("a", 19 * day, 5, "finished"), rec("a", 5 * day, 5, "finished"))
        val per = RunStats.perDay(runs, 3, now, utc)
        assertEquals(listOf(0, 1, 1), per.map { it.second })
        assertEquals(10.0, per.last().third, 0.001)
    }

    @Test fun formatsDurations() {
        assertEquals("42s", RunStats.duration(42_000))
        assertEquals("13m 25s", RunStats.duration((13 * 60 + 25) * 1000L))
        assertEquals("1h 05m", RunStats.duration(65 * 60_000L))
    }

    @Test fun reportFieldsComeFromTheProfile() {
        val fields = listOf(
            GameProfile.ReportField("level", show = "level {value}"),
            GameProfile.ReportField("seconds", duration = true),
            GameProfile.ReportField("gems", show = "gems {value}", of = "gems_max", total = "Gems found"),
            GameProfile.ReportField("mode", total = "{Value} mode"),
            GameProfile.ReportField("rare", show = "rare drop", total = "Rare drops", card = "Rare drop"),
        )
        val reps = listOf(mapOf<String, Any?>("level" to "Cave", "seconds" to 65, "gems" to 2, "gems_max" to 3, "mode" to "hard", "rare" to true),
            mapOf<String, Any?>("level" to "Hill", "gems" to 1, "gems_max" to 3, "mode" to "hard", "rare" to false))
        assertEquals("• level Cave · 1m 05s · gems 2/3 · rare drop", RunSummary.reportLine(reps[0], fields))
        val (rows, card) = RunStats.reportTotals(reps, fields)
        assertEquals(listOf("Gems found" to "3 of 6 (50%)", "Hard mode" to "2", "Rare drops" to "1"), rows)
        assertEquals(listOf("Rare drop" to 1), card)
    }
}
