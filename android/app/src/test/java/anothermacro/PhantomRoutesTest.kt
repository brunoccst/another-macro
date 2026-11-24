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

    @Test fun theMacroFileRoutesParse() {
        @Suppress("UNCHECKED_CAST")
        val file = Load(LoadSettings.builder().build()).loadFromString(File(repo, "macros/dungeon/phantom-crystal.yaml").readText()) as Map<String, Any?>
        for (w in PhantomCrystal.routes(file)) for (s in w.steps)
            assert(s.split(Regex("\\s+"))[0].lowercase() in PhantomCrystal.STEP_WORDS) { "${w.title}: unknown step '$s'" }
        assertEquals(2, PhantomCrystal.routes(mapOf("worlds" to listOf(mapOf("title" to "I", "route" to listOf("R 500")), mapOf("title" to "II")))).size)
    }
}
