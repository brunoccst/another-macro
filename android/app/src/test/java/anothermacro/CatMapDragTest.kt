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
        // a label right where the sweep's drags start
        val labels = listOf(label("The Riftbreaker", 0.72, 0.53))
        val p = CatDaily.dragStart(-0.4, 0.0, labels)
        assertEquals(0.7, p.x, 1e-9)
        assertTrue("start ${p.y} is on the label", p.y < 0.45 || p.y > 0.61)
    }

    @Test fun cornerDragFindsAFreeStartAcrossTheWidth() {
        // the sweep's drag to the top-left corner (0.6, 0.4) on Migleina's label-dense west side:
        // every start in the x 0.2 column is on a label, so it must move to another column
        val labels = listOf(label("Tower of Stars", 0.16, 0.37), label("Corinda Plains", 0.18, 0.53),
            label("Palsifal Palace", 0.28, 0.59), label("Keruri Highroad", 0.12, 0.78), label("Kira Beach", 0.34, 0.31))
        val starts = CatDaily.dragStarts(0.6, 0.4, labels)
        val p = starts.first()
        assertTrue("start (${p.x}, ${p.y}) must leave room for the drag", p.x + 0.6 <= 0.96 && p.y + 0.4 <= 0.9 && p.y >= 0.22)
        assertTrue("start (${p.x}, ${p.y}) is on a label", labels.none { l -> p.x in (l.box.x - 0.03)..(l.box.x + l.box.w + 0.03) && p.y in (l.box.y - 0.05)..(l.box.y + l.box.h + 0.05) })
    }

    @Test fun aLabelWrappedOnTwoLinesIsReadAsOne() {
        val lines = listOf(
            Ocr.Line("Nazrik, City of", Region(0.40, 0.50, 0.14, 0.04)),
            Ocr.Line("ShadowS", Region(0.43, 0.545, 0.08, 0.04)),
            Ocr.Line("Dimensional Cathedral", Region(0.60, 0.30, 0.20, 0.04)))
        val all = CatDaily.withWrapped(lines)
        val joined = all.single { Ocr.norm(it.text) == Ocr.norm("Nazrik, City of Shadows") }
        assertEquals(0.5, joined.center.y, 0.03)
        // labels far apart are not joined
        assertTrue(all.none { it.text.contains("Cathedral") && it.text.contains("Nazrik") })
    }
}
