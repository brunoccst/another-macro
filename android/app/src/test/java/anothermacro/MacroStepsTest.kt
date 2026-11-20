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

    /** A library file as the pack has it (macros/... as in the repo; templates/<folder>/... lives under src/another_macro/scenarios/<folder>/templates). */
    private fun file(path: String): String? {
        if (path.startsWith("macros/")) return File(repo, path).takeIf { it.isFile }?.readText()
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
        // the stops first, battles on the way; defeat_all is off by default for Dogma Tower: then straight to the exit
        assertTrue(lines.any { "fighting the battles met on the way" in it })
        assertTrue(lines.any { "straight to the exit once the stops are done" in it })
        val all = flat(steps("dogma-tower", mapOf("defeat_all" to "true")))
        val left = all.indexOfFirst { "The enemies have disappeared" in it }
        assertTrue(left >= 0)
        // the enemies left come after the chests, before the exit
        assertTrue(all.indexOfFirst { "chest 1" in it } < left)
        assertTrue(all.subList(left, all.size).any { "exit" in it })
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
        // no dungeon picked: those steps are left out
        assertEquals(listOf("start-game", null, null, "daily-ads", "shop-ad", null, null, "cat-scratchbook", "inbox", "stop-game"), top.map { it.macro })
        assertTrue(top.filter { it.macro != null }.all { it.sub.isNotEmpty() })
    }

    @Test fun dailyRoutineOpensUpThePickedDungeons() {
        // a picked dungeon: that macro's own steps, before and after the ads, with that colour's keys only
        val top = steps("daily-routine", mapOf("green_dungeon" to "dogma-tower", "green_party" to "3"))
        assertEquals(listOf("start-game", null, "dogma-tower", "daily-ads", "shop-ad", null, "dogma-tower", "cat-scratchbook", "inbox", "stop-game"), top.map { it.macro })
        assertTrue(top[2].note, "Party: 3" in top[2].note && "All keys: on" in top[2].note)
        assertTrue(flat(top).any { "Map 1: Basement Entrance" in it })
        assertTrue(top[1].text, top[1].skipped && "none picked" in top[1].text)
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
