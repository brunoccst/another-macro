package anothermacro.logic

import macroforge.*

/**
 * The way into every dungeon macro's run (AnotherDungeon.kt, PhantomCrystal.kt): the "!" over
 * the Another Dungeon door, tapped until the Another Dungeon map opens.
 *
 * With the `go_to_door` option on (the default, and when a macro doesn't declare it) a run
 * started anywhere on the field gets there by itself: the basic macro teleport to the Spacetime
 * Rift ("???" tab, Illusion World; Teleport.kt), then a walk left
 * from the arrival point until the door's "!" shows near the middle. It also travels when the
 * "!" in view opened something else and the field came back. Off: the run must start next to
 * the door. The places: docs/knowledge-base/learned/dungeons/README.md.
 */
object RiftDoor {
    /** The option every dungeon macro declares (default true). */
    const val OPTION = "go_to_door"

    private const val BUBBLE = "field_interact_bubble"
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

    /** The trip to the Spacetime Rift, for the basic macro teleport. */
    internal val RIFT = Teleport.Trip("???", "Spacetime Rift", "Illusion World")

    /**
     * The basic macro teleport to the Spacetime Rift ([RIFT]), then a walk left from the arrival
     * point until the door's "!" shows near the middle. The door's "!", or null if that didn't work.
     */
    internal fun travel(ctx: Context): Point? {
        MacroLog.status("Going to the Another Dungeon door (Spacetime Rift)")
        if (!Teleport.via(ctx, RIFT)) return null
        AnotherDungeon.waitForField(ctx, 5)
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
