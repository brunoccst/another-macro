package anothermacro

import anothermacro.logic.CatDaily
import anothermacro.logic.Teleport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trips other macros hand to the basic macro teleport. */
class TeleportTest {
    @Test fun tripRoundTripsThroughTheMacrosOptions() {
        val t = Teleport.Trip("???", "Spacetime Rift", "Illusion World")
        assertEquals(t, Teleport.Trip.of(t.params()))
        // an empty continent is "not given"
        assertEquals(null, Teleport.Trip.of(mapOf("era" to "Present", "place" to "Baruoki", "continent" to " ")).continent)
    }

    @Test fun clueLocationsSplitIntoPlaceAndDestination() {
        fun trip(loc: String) = CatDaily.trip(CatDaily.Clue("k", "text", loc, "Present"))
        assertEquals(Teleport.Trip("Present", "Unigan", destination = "Royal Theater"), trip("Unigan (Royal Theater)"))
        assertEquals(Teleport.Trip("Present", "Baruoki"), trip("Baruoki"))
    }

    @Test fun placeNamesCanCarryTheirContinent() {
        assertEquals("Zarbo" to "Migleina Continent", Teleport.splitPlace("Zarbo, Migleina"))
        assertEquals("Baruoki" to null, Teleport.splitPlace("Baruoki"))
        assertEquals("Snake Bone Island", Teleport.regionOf("Konium"))
    }
}
