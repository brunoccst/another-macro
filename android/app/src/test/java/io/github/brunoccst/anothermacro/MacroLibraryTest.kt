package io.github.brunoccst.anothermacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The macro library: packs, macros running macros, the diagram, saved options. */
class MacroLibraryTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "macros").isDirectory }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun unzipsIntoTheFolder() {
        val dir = createTempDir()
        MacroLibrary.unzip(zip("macros/basic/a.yaml" to "name: a", "templates/common/library.yaml" to "templates: {}").inputStream(), dir)
        assertEquals("name: a", File(dir, "macros/basic/a.yaml").readText())
        assertTrue(LibraryFiles(dir).isDir("macros/basic"))
        assertEquals(listOf("basic"), LibraryFiles(dir).list("macros").toList())
        dir.deleteRecursively()
    }

    @Test(expected = SecurityException::class) fun refusesEntriesOutsideTheFolder() {
        val dir = createTempDir()
        try { MacroLibrary.unzip(zip("../evil.yaml" to "x").inputStream(), dir) } finally { dir.deleteRecursively() }
    }

    @Test fun tagsFollowTheBranch() {
        assertEquals("macros-main", MacroLibrary.tag("main"))
        assertEquals("macros-claude-project-thread-x", MacroLibrary.tag("claude/project-thread-x"))
    }

    @Test fun everyRepoMacroParsesAndKnowsItsSteps() {
        val macros = Scenarios.parseAll(LibraryFiles(repo))
        assertTrue(macros.size >= 10)
        for (m in macros) assertEquals(m.name, emptyList<String>(), (m as FlowScenario).unsupported)
        val names = macros.map { it.name }.toSet()
        for (m in macros) for (u in (m as FlowScenario).uses) assertTrue("${m.name} runs $u", u in names)
    }

    @Test fun adsRunTheBasicMacro() {
        val macros = Scenarios.parseAll(LibraryFiles(repo)).associateBy { it.name }
        for (n in listOf("start-game", "daily-ads", "shop-ad")) assertTrue(n, "watch-ad" in (macros.getValue(n) as FlowScenario).uses)
        assertEquals("basic", macros.getValue("watch-ad").group)
        for (n in listOf("watch-ad", "battle", "grind-battles", "open-menu", "back-to-field")) assertEquals(n, "basic", macros.getValue(n).group)
        // every battle goes through the battle macro
        assertTrue("battle" in (macros.getValue("cat-scratchbook") as FlowScenario).uses)
        assertTrue("battle" in (macros.getValue("grind-battles") as FlowScenario).uses)
        assertTrue(macros.getValue("battle").params.keys.containsAll(listOf("skill_1", "skill_4")))
        assertFalse(macros.getValue("grind-battles").params.containsKey("skill_1"))
        assertTrue("back-to-field" in (macros.getValue("stop-game") as FlowScenario).uses)
        for (n in listOf("daily-ads", "shop-ad")) assertTrue(n, (macros.getValue(n) as FlowScenario).uses.containsAll(listOf("open-menu", "start-game")))
    }

    @Test fun mapsAreFoughtUntilTheEnemiesDisappear() {
        val macros = Scenarios.parseAll(LibraryFiles(repo)).associateBy { it.name }
        val grind = macros.getValue("grind-battles").params
        assertEquals("false", grind.getValue("until_cleared").default)
        assertEquals("bool", grind.getValue("until_cleared").type)
        // the dungeons fight until the banner; the fight count is only a safety cap
        for (n in listOf("dogma-tower", "burning-beast-kings-castle")) {
            val p = macros.getValue(n).params
            assertFalse(n, p.containsKey("clear_battles"))
            assertEquals(n, AnotherDungeon.CLEAR_MAX_BATTLES.toString(), p.getValue("clear_max_battles").default)
        }
    }

    @Test fun resultsCompareAsText() {
        val env = object : Expr.Env {
            override fun variable(name: String): Any? = if (name == "result") "on the field" else null
            override fun call(name: String, args: List<Any?>): Any? = null
        }
        assertFalse(Expr.truthy(Expr.eval("result != 'on the field'", env)))
        assertTrue(Expr.truthy(Expr.eval("result != 'menu open' and result != 'x'", env)))
    }

    @Test fun unknownStepsMarkAMacroUnsupported() {
        val m = FlowScenario.parse("name: x\nscreens: [field]\nhandlers:\n  field:\n    - if: true\n      then: [{teleport: home}]\n    - run: {macro: watch-ad}\n", "x")
        assertEquals(listOf("teleport"), m.unsupported)
        assertEquals(listOf("watch-ad"), m.uses)
    }

    @Test fun customMacrosAreListedUnderCustom() {
        assertEquals("custom", FlowScenario.parse("name: x\ngroup: daily\nscreens: []\n", "x", custom = true).group)
        assertEquals("daily", FlowScenario.parse("name: x\ngroup: daily\nscreens: []\n", "x").group)
    }

    @Test fun diagramShowsScreensBranchesAndCalls() {
        val m = FlowScenario.parse(File(repo, "macros/daily/shop-ad.yaml").readText(), "shop-ad")
        val sections = MacroDiagram.build(m.spec)
        assertEquals("At the start", sections[0].title)
        assertEquals("Screen 1: premium thanks", sections[1].title)
        assertEquals("No screen recognised", sections.last().title)
        fun calls(nodes: List<MacroDiagram.Node>): List<String> = nodes.flatMap { n ->
            when (n) {
                is MacroDiagram.Node.Call -> listOf(n.macro)
                is MacroDiagram.Node.Branch -> calls(n.yes) + calls(n.no)
                is MacroDiagram.Node.Loop -> calls(n.body)
                else -> emptyList()
            }
        }
        assertTrue(sections.flatMap { calls(it.nodes) }.contains("watch-ad"))
        val routine = MacroDiagram.build(FlowScenario.parse(File(repo, "macros/daily/daily-routine.yaml").readText(), "r").spec)
        assertEquals(listOf("start-game", "daily-ads", "shop-ad", "cat-scratchbook", "stop-game"),
            routine.single().nodes.map { (it as MacroDiagram.Node.Call).macro })
    }

    @Test fun conditionsReadLikeWords() {
        assertEquals("shop watch button is on screen?", MacroDiagram.condition("found('shop_watch_button')"))
        assertEquals("the game is not in front?", MacroDiagram.condition("not game_in_front()"))
        assertFalse(MacroDiagram.condition("done or stats.ads_started >= max_ads").contains("stats."))
    }

    @Test fun savedOptionsRoundTrip() {
        val p = mapOf("skill_1" to "4", "runs" to "all")
        assertEquals(p, MacroParams.decode(MacroParams.encode(p)))
    }
}
