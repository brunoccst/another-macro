package anothermacro

import macroforge.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The split between the app ("shell") and the downloadable logic pack (Logic.kt). */
class LogicPackTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "core/android/core/src/main/java/macroforge") }
        .first { it.isDirectory }

    @Test fun shellNeverNamesLogicClasses() {
        // a shell class naming a logic class would bind to the app's built-in copy, not the pack's
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "logic" }
            .filter { f -> f.readLines().any { ("macroforge.logic." in it || "anothermacro.logic." in it) && "const val PACKAGE" !in it } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun builtInLogicLoadsByName() {
        val l = Logic.current()
        assertEquals("built in", l.source)
        assertTrue("tap" in l.entry.actions)
        assertTrue(l.entry.javaClass.name.startsWith(Logic.PACKAGE + "."))
    }

    @Test fun unsupportedStepsFollowTheLoadedLogic() {
        val m = FlowScenario.parse("name: x\nscreens: [field]\nhandlers:\n  field: [{another_dungeon: dogma_tower}, {teleport: home}]\n", "x")
        assertEquals(listOf("teleport"), m.unsupported)
    }
}
