package anothermacro

import anothermacro.logic.CatRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cat spot with no area map but an `approach` and `sweep_ms` is a fixed route all the same. */
class CatApproachRouteTest {
    private fun spot(extra: Map<String, Any?>) =
        CatRoute.parse("Somewhere", mapOf("era" to "Present", "place" to "Somewhere", "area" to null) + extra)

    @Test fun approachAndSweepWithoutAnAreaMapAreAFixedRoute() {
        val s = spot(mapOf("approach" to listOf("L 3800"), "sweep_ms" to listOf(600, 700)))
        assertTrue(s.approachRoute)
        assertFalse(s.fixedWalk)
        assertEquals(600L to 700L, s.sweepMs)
        assertEquals(1, s.approach.size)
    }

    @Test fun withoutSweepOrApproachItIsNotOne() {
        assertFalse(spot(mapOf("approach" to listOf("L 3800"))).approachRoute)
        assertFalse(spot(mapOf("sweep_ms" to listOf(600, 600))).approachRoute)
        assertNull(spot(mapOf("sweep_ms" to listOf(600))).sweepMs)
    }

    @Test fun anAreaMapMakesItTheOrdinaryRoute() {
        val s = spot(mapOf("approach" to listOf("R 2000"), "sweep_ms" to listOf(600, 600),
            "area" to mapOf("segs" to listOf(listOf(0.5, 0.2, 0.8)))))
        assertFalse(s.approachRoute)
    }
}
