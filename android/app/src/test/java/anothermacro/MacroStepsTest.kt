package anothermacro

import macroforge.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "How it works": every library macro's steps in plain words, opened up and following the options. */
class MacroStepsTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "macros").isDirectory }
    private val macros = Scenarios.parseAll(LibraryFiles(repo)).filterIsInstance<FlowScenario>().associateBy { it.name }

    /** A library file as the pack has it (templates/<folder>/... lives under src/another_macro/scenarios/<folder>/templates). */
    private fun file(path: String): String? {
        val m = Regex("""^templates/([^/]+)/(.+)$""").find(path) ?: return null
        return File(repo, "src/another_macro/scenarios/${m.groupValues[1]}/templates/${m.groupValues[2]}").takeIf { it.isFile }?.readText()
    }

    private fun steps(name: String, set: Map<String, String> = emptyMap()): List<MacroSteps.Step> {
        val s = macros.getValue(name)
        val options = s.params.mapValues { it.value.default } + set
        return MacroSteps.build(s, options, find = { macros[it] }, file = ::file,
            explain = { action, arg, env -> Logic.current().entry.explain(action, arg, env) })
    }

    private fun flat(steps: List<MacroSteps.Step>, indent: String = ""): List<String> = steps.flatMap { s ->
        listOf(indent + s.text + (if (s.note.isNotEmpty()) "  [${s.note}]" else "") + (if (s.skipped) "  (skipped)" else "")) + flat(s.sub, "$indent  ")
    }

    @Test fun everyMacroSaysWhatItDoes() {
        val all = macros.keys.sorted().associateWith { flat(steps(it)) }
        // the whole text, to read over (build/how-it-works.txt)
        File("build/how-it-works.txt").writeText(all.entries.joinToString("\n\n") { (n, l) -> "== $n\n" + l.joinToString("\n") })
        for ((name, lines) in all) {
            assertTrue("$name has no steps", lines.isNotEmpty())
            assertFalse("$name: ${lines.filter { "not found" in it }}", lines.any { "(macro not found)" in it })
            // no {option} left unfilled
            assertFalse("$name: ${lines.filter { Regex("""\{[a-z_.]+\}""").containsMatchIn(it) }}", lines.any { Regex("""\{[a-z_.]+\}""").containsMatchIn(it) })
        }
    }

    @Test fun dungeonListsItsMapsAndRoute() {
        val lines = flat(steps("dogma-tower"))
        assertTrue(lines.any { "Map 1: Basement Entrance" in it })
        assertTrue(lines.any { "chest 2" in it })
        assertTrue(lines.any { "Ancient Sentry" in it })
        // clear_first is off by default for Dogma Tower: straight to the chests
        assertTrue(lines.any { "go straight to the stops" in it })
        assertTrue(flat(steps("dogma-tower", mapOf("clear_first" to "true"))).any { "The enemies have disappeared" in it })
    }

    @Test fun optionsChangeTheSteps() {
        assertTrue(flat(steps("dogma-tower", mapOf("green_key" to "false", "red_key" to "false"))).any { "no key is switched on" in it })
        assertTrue(flat(steps("dogma-tower", mapOf("red_key" to "true", "runs" to "3"))).any { "3 run(s) with red keys (Very Hard), then 3 run(s) with green keys" in it })
        assertTrue(flat(steps("dogma-tower", mapOf("party" to "4"))).any { it.startsWith("Put party 4 in front") })
        assertTrue(flat(steps("sea-of-ice", mapOf("fish" to "false"))).any { "fish 1" in it && "(skipped)" in it })
        assertTrue(flat(steps("dogma-tower", mapOf("battle.skill_1" to "4"))).any { "slot 1: skill 4" in it })
    }

    @Test fun routineOpensUpItsMacros() {
        val top = steps("daily-routine")
        assertEquals(listOf("start-game", "daily-ads", "shop-ad", "cat-scratchbook", "inbox", "stop-game"), top.map { it.macro })
        assertTrue(top.all { it.sub.isNotEmpty() })
    }

    @Test fun fillsOptionsAndPicksBranches() {
        assertEquals("3 runs", MacroSteps.fill("{runs} runs", mapOf("runs" to "3.0")))
        assertEquals("Clear first: on, Fish: off", MacroSteps.optionsIn("clear_first and not fish", mapOf("clear_first" to "true", "fish" to "false")))
        val spec = mapOf<String, Any?>("name" to "x", "how" to listOf(
            mapOf("if" to "watch_ad", "then" to listOf("Watch the ad"), "else" to listOf("Skip the ad"))))
        val x = FlowScenario(spec, "x")
        assertEquals("Watch the ad", MacroSteps.build(x, mapOf("watch_ad" to "true"), { null })[0].text)
        assertEquals("Skip the ad", MacroSteps.build(x, mapOf("watch_ad" to "false"), { null })[0].text)
        assertEquals("Watch ad: off", MacroSteps.build(x, mapOf("watch_ad" to "false"), { null })[0].note)
    }
}
