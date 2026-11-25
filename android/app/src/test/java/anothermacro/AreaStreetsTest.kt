package anothermacro

import anothermacro.logic.AreaNav
import anothermacro.logic.CatDaily
import anothermacro.logic.Teleport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading streets and stairs from an area map's street mask (the cat survey's first step). */
class AreaStreetsTest {
    private val w = 1200
    private val h = 540

    /** A made-up map: two streets, stairs between them, an icon gap, and a speck of noise. */
    private fun mask(): ByteArray {
        val m = ByteArray(w * h)
        fun fill(x1: Double, x2: Double, y1: Double, y2: Double) {
            for (y in (y1 * h).toInt() until (y2 * h).toInt()) for (x in (x1 * w).toInt() until (x2 * w).toInt()) m[y * w + x] = 1
        }
        fill(0.20, 0.80, 0.39, 0.41)   // upper street, y 0.40
        fill(0.30, 0.70, 0.59, 0.61)   // lower street, y 0.60
        fill(0.50, 0.505, 0.39, 0.61)  // stairs at x 0.5025
        // an icon over the upper street: a short break that is bridged
        for (y in (0.39 * h).toInt() until (0.41 * h).toInt()) for (x in (0.60 * w).toInt() until (0.62 * w).toInt()) m[y * w + x] = 0
        fill(0.10, 0.11, 0.80, 0.81)   // a speck
        fill(0.95, 0.99, 0.20, 0.22)   // the side buttons: left out
        return m
    }

    @Test fun readsStreetsAndStairs() {
        val l = AreaNav.streetsOf(mask(), w, h)
        assertEquals(2, l.segs.size)
        val (up, down) = l.segs.sortedBy { it.y }
        assertEquals(0.40, up.y, 0.005); assertEquals(0.20, up.x1, 0.005); assertEquals(0.80, up.x2, 0.005)
        assertEquals(0.60, down.y, 0.005); assertEquals(0.30, down.x1, 0.005); assertEquals(0.70, down.x2, 0.005)
        assertEquals(1, l.links.size)
        val s = l.links.single()
        assertEquals(0.5025, s.x, 0.004)
        assertEquals(0.40, minOf(s.y1, s.y2), 0.005); assertEquals(0.60, maxOf(s.y1, s.y2), 0.005)
    }

    @Test fun emptyMaskHasNoStreets() {
        val l = AreaNav.streetsOf(ByteArray(w * h), w, h)
        assertTrue(l.segs.isEmpty() && l.links.isEmpty())
    }

    @Test fun outcomeNamesMeasuredSpots() {
        assertEquals("", CatDaily.surveyText(emptyList()))
        assertEquals("; measured for a fixed route: Konium, Last Island", CatDaily.surveyText(listOf("Konium", "Last Island")))
    }

    @Test fun outcomeNamesCatsLookedFor() {
        assertEquals("", CatDaily.lookedText(emptyList()))
        assertEquals("; LOOKED AROUND (no fixed path) for: Konium (no fixed route)", CatDaily.lookedText(listOf("Konium (no fixed route)")))
    }

    @Test fun surveyOnlyOnTheCluesMap() {
        assertTrue(Teleport.sameMap("Nazrik, City of Shadows", "Nazrik, City of Shadows"))
        assertTrue(Teleport.sameMap("Karek Swampland", "Karek Swampland (Enter from Baruoki)"))
        assertTrue(Teleport.sameMap("Zero Point", "Zero Point - East"))
        assertTrue(!Teleport.sameMap("Konium", "Keruri Highroad"))
        assertTrue(!Teleport.sameMap("", "Keruri Highroad"))
    }
}
