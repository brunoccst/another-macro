package anothermacro

import anothermacro.logic.PhantomCrystal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/** The Phantom Crystal Dimension's fixed world routes (the macro's `worlds:` data), picked by the world's title. */
class PhantomRoutesTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "macros").isDirectory }

    private val routes = listOf(
        PhantomCrystal.WorldRoute("I", listOf("L 700", "portal 0.64 0.56")),
        PhantomCrystal.WorldRoute("II", listOf("chest 0.56 0.34")),
        PhantomCrystal.WorldRoute("World's Cape", listOf("stairs")),
    )

    @Test fun titleWordsPickTheRoute() {
        assertEquals("I", PhantomCrystal.routeFor(routes, "Phantom Crystal Dimension I")?.title)
        assertEquals("II", PhantomCrystal.routeFor(routes, "Phantom Crystal Dimension II")?.title)
        assertEquals("World's Cape", PhantomCrystal.routeFor(routes, "Phantom Crystal Dimension World's Cape")?.title)
        assertEquals("World's Cape", PhantomCrystal.routeFor(routes, "World's Cape")?.title)
    }

    @Test fun noRouteForAnUnknownOrUnreadTitle() {
        assertNull(PhantomCrystal.routeFor(routes, "Phantom Crystal Dimension IV"))
        assertNull(PhantomCrystal.routeFor(routes, "Phantom Crystal Dimension"))
        assertNull(PhantomCrystal.routeFor(routes, ""))
    }

    @Test fun onlyAStopOfItsOwnIsResumed() {
        assertEquals(true, PhantomCrystal.resumable("no way out of world 3 found"))
        assertEquals(true, PhantomCrystal.resumable("World's Cape: no Sensationalist here, but the stairs out weren't found: take them yourself"))
        assertEquals(false, PhantomCrystal.resumable("stop requested"))
        assertEquals(false, PhantomCrystal.resumable("safety stop: the shop or a purchase popup was on screen (no tap made there)"))
        assertEquals(false, PhantomCrystal.resumable("Last World: chests taken; the Sensationalist is left for you (free Treatise/Codex/Opus of your choice, then the way out)"))
        assertEquals(false, PhantomCrystal.resumable("World's Cape: the Sensationalist is here! Chests and cat taken; talk to him"))
        assertEquals(false, PhantomCrystal.resumable("an NPC offers a free Treatise/Codex/Opus: choose it yourself"))
    }

    private val placed = listOf(
        PhantomCrystal.WorldRoute("World I", listOf("R 1450"), world = 1),
        PhantomCrystal.WorldRoute("World II", listOf("chest 0.56 0.34"), world = 2),
    )

    @Test fun anUnreadTitlePicksTheRouteByPlace() {
        assertEquals("World II", PhantomCrystal.routeFor(placed, "", 2)?.title)
        assertNull(PhantomCrystal.routeFor(placed, "", 3))
        // the first world's numeral is often misread
        assertEquals("World I", PhantomCrystal.routeFor(placed, "HARD Phantom Crystal Dimension World!I", 1)?.title)
        // a title that was read but matches no route never falls back to the place
        assertNull(PhantomCrystal.routeFor(placed, "Phantom Crystal Dimension Somewhere Else", 2))
    }

    @Test fun theMacroFileRoutesParse() {
        @Suppress("UNCHECKED_CAST")
        val file = Load(LoadSettings.builder().build()).loadFromString(File(repo, "macros/dungeon/phantom-crystal.yaml").readText()) as Map<String, Any?>
        for (w in PhantomCrystal.routes(file)) for (s in w.steps)
            assert(s.split(Regex("\\s+"))[0].lowercase() in PhantomCrystal.STEP_WORDS) { "${w.title}: unknown step '$s'" }
        assertEquals(2, PhantomCrystal.routes(mapOf("worlds" to listOf(mapOf("title" to "I", "route" to listOf("R 500")), mapOf("title" to "II")))).size)
    }
}
