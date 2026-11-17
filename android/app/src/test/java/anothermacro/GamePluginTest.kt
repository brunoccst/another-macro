package anothermacro

import macroforge.*

import anothermacro.logic.AnotherEdenPlugin
import macroforge.logic.Entry
import macroforge.logic.FlowRun
import macroforge.logic.GamePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seam between the generic macro steps and the game's own (GamePlugin). */
class GamePluginTest {
    @Test fun pluginStepsAreNotGenericSteps() {
        assertTrue((FlowRun.STEPS intersect AnotherEdenPlugin.actions).isEmpty())
    }

    @Test fun everyStepIsKnown() {
        assertEquals(FlowRun.STEPS + AnotherEdenPlugin.actions, Entry().actions)
        // the steps the macros used before the split, all still there
        for (s in listOf("tap", "run", "leave_ad", "reset_view", "fight", "another_dungeon", "cat_daily", "pick_party", "find_feather", "read_cat_clues", "phantom_crystal"))
            assertTrue(s, s in Entry().actions)
    }

    @Test fun stepUsesComeFromThePlugin() {
        assertEquals(listOf("battle", "pick-party"), Entry().stepUses["another_dungeon"])
        assertTrue(Entry().stepUses.keys.all { it in AnotherEdenPlugin.actions })
    }

    @Test fun noPluginMeansGenericStepsOnly() {
        assertTrue(GamePlugin.None.actions.isEmpty())
        assertTrue(GamePlugin.None.stepUses.isEmpty())
    }
}
