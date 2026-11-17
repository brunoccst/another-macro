package io.github.brunoccst.anothermacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The event list, the daily schedule's timing and deferred Allow/Deny answers (no device needed). */
class AlwaysOnTest {
    private fun tempEvents(): File = File(Files.createTempDirectory("events").toFile(), "events.json")

    @Test fun eventsAreReportedOnceAndSurviveARestart() {
        val f = tempEvents()
        PhoneEvents.useFile(f)
        PhoneEvents.add("outcome", "Sea of ice finished (12m)", "Result: done", ok = true, now = 1_000)
        PhoneEvents.add("schedule", "Scheduled daily-routine didn't run", "Screen capture was off.", ok = false, now = 2_000)

        val first = PhoneEvents.takeUnreported()
        assertEquals(listOf("Sea of ice finished (12m)", "Scheduled daily-routine didn't run"), first.map { it.title })
        assertTrue(PhoneEvents.takeUnreported().isEmpty())

        // a new app process reads the same file: nothing old is news again, a new event is
        PhoneEvents.useFile(f)
        assertEquals(2, PhoneEvents.all().size)
        assertTrue(PhoneEvents.takeUnreported().isEmpty())
        PhoneEvents.add("approval", "You allowed: tap 'Sell'", "", ok = true, now = 3_000)
        PhoneEvents.useFile(f)
        assertEquals(listOf("You allowed: tap 'Sell'"), PhoneEvents.takeUnreported().map { it.title })
        assertEquals("outcome", PhoneEvents.last("outcome")?.kind)
    }

    @Test fun eventListKeepsOnlyTheNewest() {
        PhoneEvents.useFile(tempEvents())
        repeat(PhoneEvents.KEEP + 5) { i -> PhoneEvents.add("outcome", "run $i", "", ok = true, now = 1_000L + i) }
        val all = PhoneEvents.all()
        assertEquals(PhoneEvents.KEEP, all.size)
        assertEquals("run 5", all.first().title)
        // ids stay unique even when two events share a millisecond
        PhoneEvents.add("outcome", "same ms a", "", ok = true, now = 5)
        PhoneEvents.add("outcome", "same ms b", "", ok = true, now = 5)
        assertEquals(PhoneEvents.all().size, PhoneEvents.all().map { it.id }.toSet().size)
    }

    @Test fun describesProblemsAndText() {
        val e = PhoneEvents.Event(1, 0, "outcome", "Inbox got stuck", "Why: stuck\nWhere: doing \"open\"", ok = false)
        val text = PhoneEvents.describe(listOf(e), java.util.TimeZone.getTimeZone("UTC"))
        assertEquals("- Thu 00:00 [problem] Inbox got stuck\n  Why: stuck\n  Where: doing \"open\"", text)
    }

    @Test fun scheduleRunsAfterTheNextReset() {
        val h = 3_600_000L
        val m = 60_000L
        val day = 24 * h
        val d0 = 20 * day // midnight UTC
        // before today's reset: today, 15:10 UTC
        assertEquals(d0 + 15 * h + 10 * m, DailySchedule.nextAt(d0 + 9 * h, 10))
        // just after it: tomorrow
        assertEquals(d0 + day + 15 * h + 10 * m, DailySchedule.nextAt(d0 + 15 * h + 10 * m, 10))
        assertEquals(d0 + day + 15 * h, DailySchedule.nextAt(d0 + 23 * h, 0))
        // a delay past midnight UTC
        assertEquals(d0 + day + 1 * h, DailySchedule.nextAt(d0 + 12 * h, 10 * 60))
        assertEquals(d0 + 1 * h, DailySchedule.nextAt(d0 + 30 * m, 10 * 60))
    }

    @Test fun deferredAnswerCountsOnceForTheSameTarget() {
        Approvals.clear()
        assertNull(Approvals.take("Sell", now = 0))
        Approvals.defer(null, "Sell", "Claude wants to tap 'Sell'. Allow it?", now = 0)
        assertNull("no answer yet", Approvals.take("Sell", now = 1_000))
        Approvals.answer(null, true, now = 2_000)
        assertNull("another target doesn't use it", Approvals.take("Discard", now = 3_000))
        assertEquals(true, Approvals.take("Sell", now = 3_000))
        assertNull("used up", Approvals.take("Sell", now = 4_000))

        Approvals.defer(null, "Release", "Claude wants to tap 'Release'. Allow it?", now = 10_000)
        Approvals.answer(null, false, now = 11_000)
        assertEquals(false, Approvals.take("Release", now = 12_000))
    }

    @Test fun deferredQuestionExpires() {
        Approvals.clear()
        Approvals.defer(null, "Sell", "q", now = 0)
        Approvals.answer(null, true, now = Approvals.TTL_MS + 1)
        assertNull(Approvals.pending)
        Approvals.defer(null, "Sell", "q", now = 0)
        Approvals.answer(null, true, now = 1)
        assertNull("too late to use", Approvals.take("Sell", now = Approvals.TTL_MS + 1))
        assertFalse(Approvals.pending != null)
    }
}
