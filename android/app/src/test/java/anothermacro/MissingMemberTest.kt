package anothermacro

import macroforge.*

import anothermacro.logic.AnotherDungeon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The character the game's "please include" box asks for, as OCR reads the box. */
class MissingMemberTest {
    private fun line(text: String, y: Double, x: Double = 0.3) = Ocr.Line(text, Region(x, y, 0.4, 0.04))

    @Test fun nameFromTheDataOverTheBoxAndPartyList() {
        // as read on the phone 2026-11-18: the box over the party list, the name not on the box's line
        val box = listOf(line("In order to move to Burning Beast King's Castle (Hard) please include:", 0.40),
            line("Dungeon", 0.44), line("Verdanta", 0.50), line("Back", 0.75, 0.2), line("Sheila", 0.55),
            line("Deirdre", 0.55, 0.5), line("Move", 0.75, 0.6))
        assertEquals("Deirdre", AnotherDungeon.missingMember(box, listOf("Deirdre")))
        // no data for the dungeon: the next line is no name, so none is guessed
        assertNull(AnotherDungeon.missingMember(box, emptyList()))
    }

    @Test fun nameOnTheSameLine() {
        val box = listOf(line("In order to move to Dogma Tower (Hard) please include: Poporo", 0.40), line("Back", 0.75))
        assertEquals("Poporo", AnotherDungeon.missingMember(box, emptyList()))
    }

    @Test fun unreadableNameFallsBackToTheData() {
        // the name line not read: the next line is the box's button
        val box = listOf(line("In order to move to Burning Beast King's Castle (Hard) please include:", 0.40), line("Back", 0.75))
        assertEquals("Deirdre", AnotherDungeon.missingMember(box, listOf("Deirdre")))
        assertNull(AnotherDungeon.missingMember(box, emptyList()))
    }

    @Test fun theDataWinsOverAMangledRead() {
        val box = listOf(line("please include: Deirdre.", 0.40))
        assertEquals("Deirdre", AnotherDungeon.missingMember(box, listOf("Deirdre")))
    }
}
