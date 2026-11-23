package anothermacro.logic

import macroforge.*

/**
 * The way into every dungeon macro's run (AnotherDungeon.kt, PhantomCrystal.kt): the "!" over
 * the Another Dungeon door, tapped until the Another Dungeon map opens.
 *
 * With the `go_to_door` option on (the default, and when a macro doesn't declare it) a run
 * started anywhere on the field gets there by itself: World Map → "???" tab (Expand →
 * Illusion World when it opens on another continent) → Spacetime Rift → Yes, then a walk left
 * from the arrival point until the door's "!" shows near the middle. It also travels when the
 * "!" in view opened something else and the field came back. Off: the run must start next to
 * the door. The places: docs/knowledge-base/learned/dungeons/README.md.
 */
object RiftDoor {
    /** The option every dungeon macro declares (default true). */
    const val OPTION = "go_to_door"

    private const val BUBBLE = "field_interact_bubble"
    private val AD_CLOSE = Point(0.88, 0.03)
    private val MAP_BUTTON = Point(0.385, 0.917)
    /** The World Map's "???" tab. */
    private val ERA_OTHER = Point(0.379, 0.17)
    /** The world map's Expand button (its icon, above the label). */
    private val EXPAND_BUTTON = Point(0.844, 0.89)
    /** A "!" higher than this is over an NPC's head, not the door. */
    private const val DOOR_MIN_Y = 0.13
    private const val RIFT_STEP_MS = 350L

    /** Off only when the option says so: macros (and steps) that don't pass it travel too. */
    internal fun enabled(params: Map<String, String>): Boolean =
        params[OPTION]?.trim()?.lowercase()?.let { it !in setOf("0", "false", "no", "off", "n") } ?: true

    /** The "!" over the Another Dungeon door, not too far from the party (a "!" over an NPC sits higher). */
    fun bubble(ctx: Context): Point? {
        ctx.refresh()
        return ctx.find(BUBBLE)?.center?.takeIf { it.y > DOOR_MIN_Y }
    }

    /**
     * Open the Another Dungeon map from the field: tap the door's "!" (travelling to it first
     * when the option is on and none is in view, see [travel]). Stops the macro when it can't.
     */
    fun open(ctx: Context) {
        val go = enabled(ctx.params)
        var travelled = false
        var door = bubble(ctx)
        if (door == null) {
            if (!go) ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift), or switch on \"go to door\"")
            door = travel(ctx) ?: ctx.stop("couldn't get to the Another Dungeon door (Spacetime Rift): ${seen(ctx)}")
            travelled = true
        }
        if (tapDoor(ctx, door)) return
        // the "!" in view was over something else (a chest, a sign) and the field is back: go to the door
        if (go && !travelled && AnotherDungeon.waitForField(ctx, 3)) {
            MacroLog.i("that \"!\" didn't open the Another Dungeon map: going to the door")
            door = travel(ctx) ?: ctx.stop("couldn't get to the Another Dungeon door (Spacetime Rift): ${seen(ctx)}")
            if (tapDoor(ctx, door)) return
        }
        ctx.stop("the door didn't open the Another Dungeon map: ${seen(ctx)}")
    }

    /** Tap the "!" and give the Another Dungeon map a few reads to fade in. */
    private fun tapDoor(ctx: Context, door: Point): Boolean {
        ctx.tap(door); ctx.wait(2.5)
        for (i in 0 until 6) {
            if (ctx.readText().any { Ocr.norm(it.text) == "another dungeon" }) return true
            ctx.wait(1.0)
        }
        return false
    }

    /**
     * World Map → "???" → Spacetime Rift → Yes, then walk left from the arrival point until the
     * door's "!" shows near the middle. The door's "!", or null if that didn't work.
     */
    internal fun travel(ctx: Context): Point? {
        MacroLog.status("Going to the Another Dungeon door (Spacetime Rift)")
        var opened = false
        for (attempt in 1..3) {
            ctx.tap(MAP_BUTTON); ctx.wait(2.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            AnotherDungeon.waitForField(ctx, 5)
        }
        if (!opened) return null.also { MacroLog.i("the world map didn't open") }
        // the "???" tab opens on the continent of its last trip: Expand and pick the Rift's continent
        // when it isn't in view. Its continents list has Illusion World: without it the tap didn't
        // reach the "???" tab (the map was still opening on another era's tab), so tap it again
        for (attempt in 1..2) {
            ctx.tap(ERA_OTHER); ctx.wait(2.0)
            if (ctx.readText().any { Ocr.norm(it.text).contains("spacetime rift") }) break
            MacroLog.i("no Spacetime Rift in view: Expand -> Illusion World")
            // OCR can miss the button's label: then its place
            if (AnotherDungeon.tapText(ctx, "Expand", exact = true, tries = 1) == null) {
                MacroLog.i("no Expand label read: tapping its place")
                ctx.tap(EXPAND_BUTTON)
            }
            ctx.wait(2.0)
            if (ctx.readText().none { Ocr.norm(it.text) == "illusion world" }) {
                MacroLog.i("no Illusion World among the continents (not the \"???\" tab): ${seen(ctx)}")
                continue
            }
            AnotherDungeon.tapText(ctx, "Illusion World", exact = true)
            ctx.wait(2.0)
            break
        }
        if (AnotherDungeon.tapText(ctx, "Spacetime Rift") == null) {
            MacroLog.i("no Spacetime Rift on the map: ${seen(ctx)}")
            // a tap on the map may have asked to travel somewhere else ("Moving to ...?"): No first
            if (ctx.readText().any { Ocr.norm(it.text).contains("moving to") }) {
                ctx.readText().firstOrNull { Ocr.norm(it.text) == "no" && it.center.y > 0.4 }?.let { ctx.tapExact(it.center); ctx.wait(1.5) }
            }
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            return null
        }
        ctx.wait(1.5)
        val yes = ctx.readText().firstOrNull { Ocr.norm(it.text) == "yes" && it.center.y > 0.4 }
        if (yes != null) {
            ctx.tapExact(yes.center)
            if (!AnotherDungeon.waitForField(ctx, 30)) return null.also { MacroLog.i("the field didn't come back after the trip") }
        } else {
            // already in the Rift: the map just closes
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            AnotherDungeon.waitForField(ctx, 5)
        }
        ctx.wait(1.0)
        // the door is a short walk left of the arrival point
        repeat(16) {
            bubble(ctx)?.let { if (it.x in 0.25..0.75) { MacroLog.i("at the Another Dungeon door"); return it } }
            ctx.drag(Point(0.5, 0.65), Point(0.4, 0.65), RIFT_STEP_MS)
            ctx.wait(0.5)
        }
        return null.also { MacroLog.i("walked left in the Rift and found no door \"!\"") }
    }

    private fun seen(ctx: Context) = ctx.readText().joinToString(" | ") { it.text }.take(300)
}
