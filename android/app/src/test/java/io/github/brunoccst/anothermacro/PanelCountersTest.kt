package io.github.brunoccst.anothermacro

import io.github.brunoccst.anothermacro.logic.AnotherDungeon
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Another Dungeon panel's key and ticket counters, as OCR reads them. */
class PanelCountersTest {
    private fun line(text: String, x: Double) = Ocr.Line(text, Region(x, 0.9, 0.05, 0.04))

    @Test fun greenKeysOutOfNine() {
        val c = AnotherDungeon.panelCounters(listOf(line("5/9", 0.66), line("4/5", 0.76), line("14/50", 0.9)))
        assertEquals(listOf(listOf(5, 9), listOf(4, 5), listOf(14, 50)), c)
        assertEquals(5 to 14, AnotherDungeon.keysAndTickets(c, red = false))
        assertEquals(4 to 14, AnotherDungeon.keysAndTickets(c, red = true))
    }

    @Test fun runTogetherAndMissing() {
        val c = AnotherDungeon.panelCounters(listOf(line("10/10", 0.66), line("5/515/5O", 0.8)))
        assertEquals(listOf(listOf(10, 10), listOf(5, 5), listOf(15, 50)), c)
        assertEquals(10 to 15, AnotherDungeon.keysAndTickets(c, red = false))
        // the tickets hidden under the app's buttons: keys still read, tickets unknown
        assertEquals(5 to null, AnotherDungeon.keysAndTickets(AnotherDungeon.panelCounters(listOf(line("5/9", 0.66))), red = false))
    }
}
