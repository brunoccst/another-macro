package anothermacro

import anothermacro.logic.CatDaily
import macroforge.Ocr
import macroforge.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** World-map drags must not start on a place label: the game then doesn't move the map. */
class CatMapDragTest {
    private fun label(text: String, x: Double, y: Double) = Ocr.Line(text, Region(x - 0.08, y - 0.025, 0.16, 0.05))

    @Test fun dragStartsInTheMiddleWhenItIsFree() {
        val p = CatDaily.dragStart(-0.4, 0.0, listOf(label("Rinde", 0.58, 0.77)))
        assertEquals(0.7, p.x, 1e-9)
        assertEquals(0.55, p.y, 1e-9)
    }

    @Test fun dragStartAvoidsALabel() {
        // the phone, 2026-11-17: "The Riftbreaker" sat right where the sweep's drags started
        val labels = listOf(label("The Riftbreaker", 0.72, 0.53))
        val p = CatDaily.dragStart(-0.4, 0.0, labels)
        assertEquals(0.7, p.x, 1e-9)
        assertTrue("start ${p.y} is on the label", p.y < 0.45 || p.y > 0.61)
    }
}
