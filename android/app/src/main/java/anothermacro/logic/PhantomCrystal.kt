package anothermacro.logic

import macroforge.*

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The Phantom Crystal Dimension, entered with a White Key:
 *
 *  1. the "!" over the Another Dungeon door (from anywhere on the field with the `go_to_door`
 *     option: RiftDoor.kt) → the glowing "???" tab → "Phantom Crystal
 *     Dimension" → Hard ("Using White Key×1") → Move;
 *  2. every world: open every chest (each shows a "!" when the party is close), tap the cats,
 *     then take a portal (any other "!"). A "!" that opens the paw stamp menu is cancelled
 *     (claiming is left to the user). A world with a fixed route (the macro's `worlds:` data,
 *     picked by the world's title) is walked by it; any other world, or a route that loses its
 *     way, is swept: walked end to end in short steps, looking for "!"s. A swept world logs a
 *     draft route ("route draft for world ...") to copy into the data;
 *  3. a world that moves on by itself once emptied is followed;
 *  4. the Cape or the final world: its chests, then the "!" on the stairs out; Congratulations;
 *  5. the Last World: the run stops after the chests, before talking to the Sensationalist, so
 *     the user talks to them and chooses the reward. The World's Cape: the run looks for their "!"
 *     (never talking to them) and stops for the user only when it is there; otherwise it takes
 *     the stairs out and finishes the dimension.
 *
 * No area map is used. Nothing is bought: every tap goes through the same purchase guard as the
 * rest of the app. The dimension itself: docs/knowledge-base/learned/phantom-crystal.md.
 */
object PhantomCrystal {
    private const val ICONS = "templates/another_dungeon/"
    private val AD_CLOSE = Point(0.88, 0.03)
    private val PHANTOM_TAB = Point(0.375, 0.165)
    private val EXPAND = Point(0.844, 0.9)

    private class Run(val ctx: Context) {
        val start = System.currentTimeMillis()
        var world = 1
        val items = LinkedHashMap<String, Int>()
        var chests = 0
        var stamps = 0
        var cape = false
        /** The Last World: after its chests the run stops before the Sensationalist (the user talks to them). */
        var last = false
        /** The height (screen y) of the Cape cat's "!", once its stamp was taken: a person's "!" sits higher. */
        var catY: Double? = null
        /** "!" spots that open a menu (the stamp cat, the notice board): screen x in the current world. */
        val skip = ArrayList<Point>()
        /** The fixed routes of the macro's `worlds:` data. */
        val routes: List<WorldRoute> = loadRoutes(ctx)
        /** The current world's title, as its banner was read on arrival ("" = not read). */
        var title = ""
        /** The next world's title, read during the fade into it (its banner hides the field's buttons, so it is gone once they show). */
        var nextTitle = ""
        /** The current world is walked by its fixed route (not swept). */
        var routed = false
        // the draft route: how far the party walked from where it arrived (walk.yaml distance, + = right),
        // and the steps so far
        val walkTable by lazy { Route.table(ctx) }
        var pos = 0.0
        var mark = 0.0
        val draft = ArrayList<String>()
        /** Every world that had to be looked around (no fixed route, or the route lost its way): told to the user at the end. */
        val swept = ArrayList<String>()
        fun newWorld() { skip.clear(); title = nextTitle; nextTitle = ""; routed = false; pos = 0.0; mark = 0.0; draft.clear() }
    }

    /** The macro file the `worlds:` routes are read from. */
    private const val MACRO = "phantom-crystal"

    /**
     * A world's fixed route (the macro's `worlds:` list): [title] = words of the world's title
     * banner; [steps] from where the party arrives:
     * `L 1200` / `R 1200` hold left/right that many ms from rest, `U` / `D` flick up/down,
     * `wait 1.5`, `chest [x y]` / `cat [x y]` tap the "!" there (the one nearest that screen spot,
     * else the one nearest the middle) and take the item / stamp, `portal [x y]` take the way on,
     * `stairs [x y]` take the stairs out of the dimension, `sensationalist` look for the
     * Sensationalist's "!" here (the run stops for the user when it shows), `moves_on` wait for
     * the world to move on by itself. [world] = the world's place in the run (1 = the first world):
     * the route is picked by it only when the title wasn't read at all (World I always, being the
     * first room).
     */
    data class WorldRoute(val title: String, val steps: List<String>, val world: Int? = null)

    /** Every step word a route may use (also checked by the repository's tests). */
    val STEP_WORDS = setOf("l", "r", "u", "d", "wait", "chest", "cat", "portal", "stairs", "sensationalist", "moves_on")

    /** The `worlds:` routes of a parsed macro file. */
    @Suppress("UNCHECKED_CAST")
    internal fun routes(macroFile: Map<String, Any?>): List<WorldRoute> =
        (macroFile["worlds"] as? List<*>).orEmpty().mapNotNull { w ->
            val m = w as? Map<String, Any?> ?: return@mapNotNull null
            val t = m["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WorldRoute(t, (m["route"] as? List<*>).orEmpty().map { it.toString().trim() }.filter { it.isNotEmpty() },
                m["world"]?.toString()?.trim()?.toIntOrNull())
        }

    private fun loadRoutes(ctx: Context): List<WorldRoute> =
        runCatching { (ctx.findMacro(MACRO) as? FlowScenario)?.spec?.let { routes(it) } }.getOrNull() ?: emptyList()

    /** The world's own name in its title banner: the words after "Phantom Crystal Dimension". */
    internal fun worldName(title: String): String = Ocr.norm(title).replace(Regex("^.*?phantom crystal dimension"), "").trim()

    /**
     * The route for world number [world] whose banner read [title]: its title words in the banner,
     * else a close read; when the title wasn't read (or for the first world, whose numeral OCR
     * misreads), the route kept for that place in the run.
     */
    internal fun routeFor(routes: List<WorldRoute>, title: String, world: Int = 0): WorldRoute? {
        val t = worldName(title)
        val byTitle = if (t.isEmpty()) null else
            routes.firstOrNull { val w = worldName(it.title); w.isNotEmpty() && Regex("(^| )${Regex.escape(w)}( |$)").containsMatchIn(t) }
                ?: routes.map { it to Ocr.similarity(worldName(it.title), t) }.filter { it.second >= 0.8 }.maxByOrNull { it.second }?.first
        if (byTitle != null) return byTitle
        return if (world > 0 && (t.isEmpty() || world == 1)) routes.firstOrNull { it.world == world } else null
    }

    fun run(ctx: Context): String {
        val r = Run(ctx)
        val outcome = try { inner(r) } catch (s: StopScenario) { logDraft(r); s.message ?: "stopped" }
        val secs = (System.currentTimeMillis() - r.start) / 1000
        val items = r.items.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val text = "$outcome — ${secs / 60}m ${secs % 60}s; worlds ${r.world}${if (r.cape) " (ended in the Cape)" else ""}; " +
            "chests ${r.chests}, stamps ${r.stamps}; items: $items" +
            if (r.swept.isEmpty()) "" else "; LOOKED AROUND (no fixed path) in: ${r.swept.joinToString("; ")} (draft routes in the log)"
        runCatching {
            val o = org.json.JSONObject().put("when", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.start)))
                .put("dungeon", "Phantom Crystal Dimension").put("seconds", secs).put("outcome", outcome).put("worlds", r.world)
                .put("cape", r.cape).put("chests", r.chests).put("stamps", r.stamps).put("items", org.json.JSONObject(r.items as Map<*, *>))
                .put("looked_around", org.json.JSONArray(r.swept))
            java.io.File(ctx.debugDir.parentFile, "runs.log").appendText(o.toString() + "\n")
        }.onFailure { MacroLog.e("could not save the run report", it) }
        MacroLog.status(text)
        return text
    }

    private fun inner(r: Run): String {
        val ctx = r.ctx
        // a world's title or an item banner hides the field's buttons for a few seconds: look again before
        // taking this for the outside
        var isInside = inside(ctx)
        for (i in 0 until 6) {
            if (isInside) break
            // an item or stamp banner left open (it hides the bar): close it
            val top = ctx.readText(Region(0.15, 0.0, 0.7, 0.25)).joinToString(" ") { Ocr.norm(it.text) }
            if ("obtained" in top || "stamp" in top) dismiss(ctx) else ctx.wait(1.0)
            isInside = inside(ctx)
        }
        if (!isInside) enter(r)
        while (true) {
            MacroLog.status("Phantom Crystal Dimension: world ${r.world}${if (r.cape) " (the Cape)" else ""}")
            when (clearWorld(r)) {
                Next.WORLD -> { logDraft(r); r.world++; r.newWorld() }
                Next.END -> { logDraft(r); return finish(r) }
            }
        }
    }

    private enum class Next { WORLD, END }

    // --- 1. entering ------------------------------------------------------------------
    /** Inside already: a field whose bottom bar has only Menu and Rewards (outside it has Quests, Tomes, Diary, Map). */
    internal fun inside(ctx: Context): Boolean {
        ctx.refresh()
        if (ctx.find("field_menu_button") == null) return false
        // OCR may read the bar as one line ("Menu Rewards")
        val words = ctx.readText(Region(0.0, 0.82, 0.6, 0.18)).flatMap { Ocr.norm(it.text).split(' ') }
        // ...or as one word ("MenuRewards")
        return words.any { "rewards" in it } && words.none { it == "quests" || it == "map" || it == "diary" || "quests" in it || "diary" in it }
    }

    private fun enter(r: Run) {
        val ctx = r.ctx
        MacroLog.status("Entering the Phantom Crystal Dimension")
        // the door's "!", or (option go_to_door, on by default) the trip to it from anywhere on the field
        RiftDoor.open(ctx)
        ctx.tapExact(PHANTOM_TAB); ctx.wait(2.0)
        fun phantomLabel() = ctx.readText().firstOrNull { Ocr.similarity(it.text, "Phantom Crystal") >= 0.7 || Ocr.norm(it.text).startsWith("phantom crystal") }
        var label = phantomLabel()
        // the "???" tab opens on the continent of its last trip (Underworld after an Underworld run, 2026-11-23:
        // the run stopped with "no White Key" while one was held): Expand, then Illusion World
        if (label == null) {
            MacroLog.i("no Phantom Crystal Dimension in view: Expand -> Illusion World")
            val expand = ctx.readText().firstOrNull { Ocr.norm(it.text) == "expand" }
            if (expand != null) ctx.tapExact(expand.center) else ctx.tapExact(EXPAND)
            ctx.wait(2.0)
            ctx.readText().firstOrNull { Ocr.norm(it.text) == "illusion world" }?.let { ctx.tapExact(it.center); ctx.wait(2.0) }
            label = phantomLabel()
        }
        if (label == null) {
            ctx.tapExact(AD_CLOSE); ctx.wait(1.5)
            ctx.stop("no White Key: the Phantom Crystal Dimension isn't open")
        }
        ctx.tapExact(label.center); ctx.wait(2.0)
        // the level's icon in front of "Hard" can be read into the word ("OHard", 2026-11-23: the run stopped
        // with the entry on screen), and the panel slides in from the right: look for up to 8 s
        fun hardEntry() = ctx.readText().firstOrNull { val t = Ocr.norm(it.text).replace(" ", ""); t.endsWith("hard") && t.length <= 6 && it.center.x > 0.6 }
        var hard = hardEntry()
        val until = System.currentTimeMillis() + 8000
        while (hard == null && System.currentTimeMillis() < until) { ctx.wait(0.5); hard = hardEntry() }
        if (hard == null) {
            // close the Another Dungeon screen, so the next macro of a routine starts from the field
            ctx.tapExact(AD_CLOSE); ctx.wait(1.5)
            ctx.stop("no Hard entry for the Phantom Crystal Dimension")
        }
        ctx.tapExact(hard.center); ctx.wait(2.0)
        val lines = ctx.readText()
        if (lines.none { Ocr.norm(it.text).contains("white key") }) {
            lines.firstOrNull { Ocr.norm(it.text) == "back" }?.let { ctx.tapExact(it.center) }
            ctx.stop("the entry doesn't use a White Key: ${lines.joinToString(" | ") { it.text }.take(200)}")
        }
        // never Skip here: it would spend a Cat Express Ticket
        val move = lines.firstOrNull { Ocr.norm(it.text) == "move" } ?: ctx.stop("no Move button")
        ctx.tapExact(move.center)
        ctx.count("entered")
        waitForWorld(ctx, 30) || ctx.stop("didn't arrive in the Phantom Crystal Dimension")
    }

    // --- 2-4. a world -------------------------------------------------------------------
    /**
     * Take everything in this world, then leave it. Walks to one end and back so every chest
     * comes close enough to show its "!", tapping chests (and cats) as they show.
     */
    private fun clearWorld(r: Run): Next {
        val ctx = r.ctx
        // the world's title banner ("Phantom Crystal Dimension <world>") shows for a few seconds on arrival
        repeat(4) {
            ctx.refresh()
            val title = ctx.readText(Region(0.1, 0.0, 0.8, 0.3)).filter { Ocr.norm(it.text) != "am" }.joinToString(" ") { it.text.trim() }
            if (Ocr.norm(title).length > Ocr.norm(r.title).length) r.title = title
            if (!r.last && "last world" in Ocr.norm(r.title)) { r.last = true; MacroLog.i("the Last World") }
            if (!r.cape && "cape" in Ocr.norm(r.title)) { r.cape = true; MacroLog.i("the World's Cape") }
            if (!r.last) ctx.wait(0.8)
        }
        MacroLog.i("World ${r.world}: title '${r.title}'")
        val route = routeFor(r.routes, r.title, r.world)
        if (route == null) sweptNote(r, "no fixed route")
        else {
            r.routed = true
            when (followRoute(r, route)) {
                RouteOut.WORLD -> return Next.WORLD
                RouteOut.END -> return Next.END
                RouteOut.DONE -> return afterRoute(r, route)
                // a "!" the route expected wasn't there: look around from here
                RouteOut.LOST -> { r.routed = false; sweptNote(r, "the route lost its way") }
            }
        }
        var dir = -1.0
        var ends = 0
        var steps = 0
        while (ends < 2 && steps < 40) {
            steps++
            // chests first, then cats
            when (takeNearby(r)) {
                Taken.LEFT -> return Next.WORLD
                Taken.END -> return Next.END
                Taken.SOME -> continue
                Taken.NONE -> {}
            }
            // a closed chest in view without a "!" yet: walk toward it
            val chest = closedChests(ctx).minByOrNull { abs(it.x - 0.5) }
            if (steps % 5 == 1) MacroLog.i("World ${r.world}: looking around (step $steps, ends $ends${chest?.let { ", closed chest at x ${"%.2f".format(it.x)}" } ?: ""})")
            if (chest != null && abs(chest.x - 0.5) > 0.05) {
                walk(r, if (chest.x < 0.5) -1.0 else 1.0, 350)
                continue
            }
            if (!stride(r, dir, 700)) { ends++; dir = -dir }
        }
        // the Last World: its chests are taken; the Sensationalist's reward is the user's choice
        if (r.last) ctx.stop(LAST_WORLD_STOP)
        // the Cape: never take the way out while the Sensationalist may be there; the user looks and
        // finishes the dimension
        if (r.cape) {
            capeCats(r)
            // stop only when the Sensationalist's "!" is seen, else take the stairs out
            if (sensationalistInCape(r)) ctx.stop(CAPE_STOP)
            return capeExit(r)
        }
        // nothing left to take: a portal (or, in the Cape / final world, the stairs out)
        return leave(r)
    }

    private const val LAST_WORLD_STOP = "Last World: chests taken; the Sensationalist is left for you (free Treatise/Codex/Opus of your choice, then the way out)"
    private const val CAPE_STOP = "World's Cape: the Sensationalist is here! Chests and cat taken; " +
        "talk to him and choose your free Treatise/Codex/Opus, then take the stairs out"

    private enum class RouteOut { WORLD, END, DONE, LOST }

    /**
     * Walk the world by its fixed route. LOST: a "!" the route expected didn't show (or the way on
     * didn't take the party on): the sweep takes over from where the party stands.
     */
    private fun followRoute(r: Run, route: WorldRoute): RouteOut {
        val ctx = r.ctx
        MacroLog.status("Phantom Crystal Dimension: world ${r.world}${if (r.cape) " (the Cape)" else ""}, fixed route")
        MacroLog.i("World ${r.world}: fixed route '${route.title}': ${route.steps.joinToString(", ")}")
        // the unfolded floating buttons' label can sit over a "!" near the right edge
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        for ((i, step) in route.steps.withIndex()) {
            val parts = step.split(Regex("\\s+"))
            val word = parts[0].lowercase()
            when (word) {
                "l", "r" -> walk(r, if (word == "r") 1.0 else -1.0, parts.getOrNull(1)?.toDoubleOrNull()?.toLong() ?: 500)
                "u", "d" -> AreaNav.flick(ctx, up = word == "u")
                "wait" -> ctx.wait(parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
                "moves_on" -> when (afterLeaving(r)) {
                    Taken.LEFT -> return RouteOut.WORLD
                    Taken.END -> return RouteOut.END
                    else -> { MacroLog.i("World ${r.world}: route step ${i + 1}: the world didn't move on"); return RouteOut.LOST }
                }
                "sensationalist" -> {
                    MacroLog.status("World's Cape: is the Sensationalist here?")
                    var seen = false
                    for (look in 0 until 3) {
                        seen = personSeen(r, notStairs(ctx, ctx.refresh()))
                        if (seen) break
                        ctx.wait(0.5)
                    }
                    if (seen) ctx.stop(CAPE_STOP)
                    MacroLog.i("World's Cape: no Sensationalist")
                }
                "chest", "cat", "portal", "stairs" -> {
                    val x = parts.getOrNull(1)?.toDoubleOrNull()
                    val y = parts.getOrNull(2)?.toDoubleOrNull()
                    val b = routeBubble(r, word, if (x != null && y != null) Point(x, y) else null)
                    if (b == null) {
                        MacroLog.i("World ${r.world}: route step ${i + 1} '$step': no \"!\" there: looking around instead")
                        return RouteOut.LOST
                    }
                    val had = r.stamps
                    when (tapBubble(r, b)) {
                        Taken.LEFT -> return if (word == "stairs") RouteOut.END else RouteOut.WORLD
                        Taken.END -> return RouteOut.END
                        else -> {
                            if (word == "cat" && r.cape && r.stamps > had) r.catY = b.y
                            if (word == "portal" || word == "stairs") {
                                MacroLog.i("World ${r.world}: route step ${i + 1} '$step': still in this world: looking around instead")
                                return RouteOut.LOST
                            }
                        }
                    }
                }
                else -> MacroLog.i("World ${r.world}: unknown route step '$step'")
            }
        }
        return RouteOut.DONE
    }

    /** The route ended in its world without leaving it: what the world still needs. */
    private fun afterRoute(r: Run, route: WorldRoute): Next {
        if (r.last) r.ctx.stop(LAST_WORLD_STOP)
        if (r.cape) {
            val words = route.steps.map { it.substringBefore(' ').lowercase() }
            if ("cat" !in words) capeCats(r)
            if ("sensationalist" !in words && sensationalistInCape(r)) r.ctx.stop(CAPE_STOP)
            return capeExit(r)
        }
        return leave(r)
    }

    /**
     * The "!" a route step taps: the one nearest the step's spot [at] when it gives one, else the
     * one nearest the middle (in the Cape, the stairs' "!" only for `stairs`, never for the
     * others). When none shows, the party is nudged a little either way (the route's holds can
     * end a little short or long).
     */
    private fun routeBubble(r: Run, kind: String, at: Point?): Point? {
        val ctx = r.ctx
        // a cat walks about: its "!" is looked for longer and further from the spot
        val lookMs = if (kind == "cat") 6000L else 2500L
        val near = if (kind == "cat") 0.25 else 0.12
        fun pick(reach: Double): Point? {
            val f = ctx.refresh()
            val all = bubbles(ctx, f).filter { b -> r.skip.none { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 } }
            val ups = if (r.cape) stairsArrows(ctx, f) else emptyList()
            val fit = when {
                !r.cape -> all
                kind == "stairs" -> all.filter { isStairs(it, ups) }
                else -> all.filter { !isStairs(it, ups) }
            }
            if (at == null) return fit.minByOrNull { abs(it.x - 0.5) }
            return fit.filter { hypot(it.x - at.x, it.y - at.y) < reach }.minByOrNull { hypot(it.x - at.x, it.y - at.y) }
        }
        fun look(ms: Long, reach: Double): Point? {
            val until = System.currentTimeMillis() + ms
            while (true) {
                pick(reach)?.let { return it }
                if (System.currentTimeMillis() > until) return null
                ctx.wait(0.3)
            }
        }
        look(lookMs, near)?.let { return it }
        // right, back, left, back: after a nudge the "!" sits a little off its spot on screen
        for (dir in listOf(1.0, -1.0, -1.0, 1.0)) {
            MacroLog.i("World ${r.world}: no \"!\" for '$kind' yet: a nudge ${if (dir > 0) "right" else "left"}")
            walk(r, dir, NUDGE_MS)
            look(1000, near * 2)?.let { return it }
        }
        return null
    }

    /** The world is looked around instead of walked by a fixed route: logged, shown and told at the run's end. */
    private fun sweptNote(r: Run, why: String) {
        val name = worldName(r.title).ifEmpty { "title not read" }
        r.swept += "world ${r.world} '$name' ($why)"
        MacroLog.i("LOOKING AROUND: world ${r.world} '$name': $why")
        MacroLog.status("World ${r.world}: $why, looking around (you'll be told at the end)")
    }

    /** A route step's nudge when its "!" doesn't show. */
    private const val NUDGE_MS = 300L

    private enum class Taken { NONE, SOME, LEFT, END }

    /** Tap the "!"s on screen that sit over a chest or a cat. */
    private fun takeNearby(r: Run): Taken {
        val ctx = r.ctx
        val frame = ctx.refresh()
        val bubbles = bubbles(ctx, frame)
        val chests = closedChests(ctx)
        // a bubble above a closed chest
        val overChest = bubbles.firstOrNull { b -> chests.any { abs(it.x - b.x) < 0.04 && it.y > b.y && it.y - b.y < 0.32 } }
        if (overChest != null) return tapBubble(r, overChest)
        // a bubble on something that walks: a cat (portals stand still)
        val others = bubbles.filter { b -> r.skip.none { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 } }
        if (others.isNotEmpty()) {
            // one second look for all of them (one per bubble cost ~2 s each)
            Thread.sleep(500)
            val again = bubbles(ctx, ctx.refresh())
            for (b in others) {
                val moved = again.none { abs(it.x - b.x) < 0.006 && abs(it.y - b.y) < 0.01 } && again.any { abs(it.x - b.x) < 0.08 && abs(it.y - b.y) < 0.05 }
                if (moved) return tapBubble(r, again.minByOrNull { abs(it.x - b.x) }!!)
            }
        }
        return Taken.NONE
    }

    /** Tap a "!" and see what it was: an item, a stamp, a menu, or a way out. */
    private fun tapBubble(r: Run, b: Point): Taken {
        val ctx = r.ctx
        MacroLog.i("World ${r.world}: tapping the \"!\" at (${"%.2f".format(b.x)}, ${"%.2f".format(b.y)})")
        ctx.tapExact(b); ctx.wait(1.0)
        // the chest's banner can take ~3 s to show: read until there's text, a fade, or ~4.5 s
        // (the app's own "AM" badge is always read: it is no banner, so it doesn't end the wait)
        fun game() = ctx.readText().filter { Ocr.norm(it.text) != "am" }
        var lines = game()
        val readUntil = System.currentTimeMillis() + 3500
        while (lines.isEmpty() && System.currentTimeMillis() < readUntil) {
            ctx.refresh(); if (dark(ctx)) break
            ctx.wait(0.3); lines = game()
        }
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        MacroLog.i("World ${r.world}: \"!\" shows: ${lines.joinToString(" | ") { it.text }.take(120)}")
        when {
            "treatise" in all || "codex" in all || "opus" in all || "sensationalist" in all -> ctx.stop("an NPC offers a free Treatise/Codex/Opus: choose it yourself")
            // arriving in a world can come with no fade the watcher sees (an autosave): the world's title
            // is checked too
            "autosaving" in all -> { MacroLog.i("World ${r.world}: autosave: a new world"); ctx.wait(1.5); return Taken.LEFT }
            "stamp" in all && ("you got" in all || "filled out" in all) -> {
                r.stamps++; MacroLog.i("paw stamp"); note(r, "cat", b); dismiss(ctx); return Taken.SOME
            }
            "obtained" in all -> {
                noteItem(r, lines); r.chests++; ctx.count("chests"); note(r, "chest", b); dismiss(ctx); return Taken.SOME
            }
            "trained" in all -> {
                note(r, "chest", b)
                // a bonus world's straw dummy: "You trained using the straw dummy." on black, then the item
                dismissBanner(ctx)
                val got = ctx.readText()
                if (got.any { "obtained" in Ocr.norm(it.text) }) { noteItem(r, got); r.chests++; ctx.count("chests") }
                dismissBanner(ctx)
                return Taken.SOME
            }
            "what would you like to do" in all || lines.any { Ocr.norm(it.text) == "cancel" } -> {
                // the paw stamp menu (king cat / notice board): leave it, and don't tap here again
                lines.firstOrNull { Ocr.norm(it.text) == "cancel" }?.let { ctx.tapExact(it.center) }
                r.skip += b
                ctx.wait(1.5)
                return Taken.SOME
            }
        }
        val out = afterLeaving(r)
        when (out) {
            Taken.LEFT -> note(r, if (r.cape) "stairs" else "portal", b)
            Taken.END -> note(r, "stairs", b)
            else -> {}
        }
        return out
    }

    /**
     * The World's Cape's cat sits still, so the walking-cat check doesn't take it. Walk the Cape in
     * short steps and tap each still "!" that is neither the stairs out (a "!" with the floor's up
     * arrow under it, high at y ~0.23) nor a person (their "!" sits higher than a cat's ~0.29).
     */
    private fun capeCats(r: Run) {
        val ctx = r.ctx
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        val had = r.stamps
        var dir = -1.0
        var ends = 0
        for (step in 0 until 30) {
            val f = ctx.refresh()
            val still = notStairs(ctx, f)
            // two "!"s off the stairs: the cat and a person (the Sensationalist), whose "!" sits higher: never tap them
            val top = if (still.size >= 2) still.minByOrNull { it.y }!!.y else -1.0
            val cats = still.filter { b ->
                b.y > CAT_MIN_Y && b.y > top + 0.015 && r.skip.none { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 }
            }
            for (b in cats) {
                MacroLog.i("World's Cape: a still \"!\" at (${"%.2f".format(b.x)}, ${"%.2f".format(b.y)}): the cat?")
                r.skip += b
                when (tapBubble(r, b)) {
                    Taken.LEFT, Taken.END -> return
                    else -> {}
                }
                if (r.stamps > had) { r.catY = b.y; MacroLog.i("World's Cape: the cat's \"!\" is at y ${"%.2f".format(b.y)}"); return }
            }
            if (!stride(r, dir, 500)) { if (++ends > 1) break; dir = -dir }
        }
    }

    /** The "!"s on [f] that aren't over the stairs out. */
    private fun notStairs(ctx: Context, f: Frame): List<Point> {
        val ups = stairsArrows(ctx, f)
        return bubbles(ctx, f).filter { b -> !isStairs(b, ups) }
    }

    /**
     * The stairs out's "!": the floor's up arrow under it, or (where there is no such arrow) a "!"
     * this high on screen.
     */
    private fun isStairs(b: Point, ups: List<Point>) = b.y < STAIRS_MAX_Y || ups.any { overArrow(b, it) }

    private fun stairsArrows(ctx: Context, f: Frame) = findAll(f.gray, icon(ctx, "ground_arrow_up"), listOf(0.9, 1.0, 1.1), 0.75)

    private fun overArrow(b: Point, up: Point) = abs(up.x - b.x) < 0.08 && up.y > b.y && up.y - b.y < 0.45

    /**
     * Is the Sensationalist in the World's Cape? Looked for without talking to them (the user picks
     * the reward): besides the stairs out, the Cape's only "!"s are the cat's and theirs, and theirs
     * sits higher than the cat's. Walks the whole Cape in short steps. When unsure it says yes: a
     * missed reward costs more than a stop for nothing.
     */
    private fun sensationalistInCape(r: Run): Boolean {
        val ctx = r.ctx
        MacroLog.status("World's Cape: is the Sensationalist here?")
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        var dir = 1.0
        var ends = 0
        for (step in 0 until 30) {
            if (personSeen(r, notStairs(ctx, ctx.refresh()))) return true
            if (!stride(r, dir, 500)) { if (++ends > 1) break; dir = -dir }
        }
        MacroLog.i("World's Cape: no Sensationalist")
        return false
    }

    /**
     * The Sensationalist's "!" among [still] (the "!"s off the stairs): two of them (the cat's and
     * theirs), or one higher than a cat's.
     */
    private fun personSeen(r: Run, still: List<Point>): Boolean {
        // the cat's "!" height when known, else a little above where cats' "!"s sit (~0.29)
        val personBelow = r.catY?.let { it - 0.015 } ?: CAT_MIN_Y
        val ys = still.joinToString { "%.2f".format(it.y) }
        if (still.size >= 2) { MacroLog.i("World's Cape: two \"!\"s off the stairs (y $ys): the cat and the Sensationalist"); return true }
        val b = still.firstOrNull() ?: return false
        if (b.y < personBelow) {
            MacroLog.i("World's Cape: a \"!\" at (${"%.2f".format(b.x)}, ${"%.2f".format(b.y)}) above a cat's (${"%.2f".format(personBelow)}): the Sensationalist")
            return true
        }
        return false
    }

    /** No Sensationalist: walk to the stairs out (their "!" shows only close by) and take them. */
    private fun capeExit(r: Run): Next {
        val ctx = r.ctx
        MacroLog.status("World's Cape: no Sensationalist, taking the stairs out")
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        var dir = 1.0
        var ends = 0
        for (step in 0 until 30) {
            val f = ctx.refresh()
            val ups = stairsArrows(ctx, f)
            val stairs = bubbles(ctx, f).filter { b -> isStairs(b, ups) }.minByOrNull { abs(it.x - 0.5) }
            if (stairs != null) {
                when (tapBubble(r, stairs)) {
                    // the Cape's stairs end the dimension: the game autosaves before Congratulations, which read as
                    // "a new world" and left the run walking the Congratulations screen as world 5 (2026-11-23)
                    Taken.END, Taken.LEFT -> return Next.END
                    else -> {}
                }
            }
            if (!stride(r, dir, 500)) { if (++ends > 2) break; dir = -dir }
        }
        ctx.stop("World's Cape: no Sensationalist here, but the stairs out weren't found: take them yourself")
    }

    /** Take any "!" left (not a known menu): a portal, the stairs out. */
    private fun leave(r: Run): Next {
        val ctx = r.ctx
        // the unfolded floating buttons' label can sit over a "!" near the right edge and hide it from the match
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        // a door's "!" shows only when the party stands close to it: walk the world in short steps,
        // one way to its end, then back
        var dir = 1.0
        var ends = 0
        for (step in 0 until 40) {
            val left = bubbles(ctx, ctx.refresh()).filter { b -> r.skip.none { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 } }
            for (b in left.sortedBy { abs(it.x - 0.5) }) {
                MacroLog.status("World ${r.world}: taking the way on")
                when (tapBubble(r, b)) {
                    Taken.LEFT -> return Next.WORLD
                    Taken.END -> return Next.END
                    else -> {}
                }
            }
            if (!stride(r, dir, 500)) { if (++ends > 2) break; dir = -dir }
        }
        ctx.stop("no way out of world ${r.world} found")
    }

    /** After a tap that wasn't an item/stamp/menu: did we leave the world (or the dimension)? */
    private fun afterLeaving(r: Run): Taken {
        val ctx = r.ctx
        MacroLog.i("World ${r.world}: not an item, stamp or menu: watching for a new world")
        r.nextTitle = ""
        val end = System.currentTimeMillis() + 25_000
        var faded = false
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            if ("congratulations" in all || "obtained dungeon items" in all) return Taken.END
            if ("autosaving" in all) { MacroLog.i("autosave: a new world"); ctx.wait(1.5); return Taken.LEFT }
            if (lines.any { Ocr.norm(it.text) == "another dungeon" }) return Taken.END
            // a late item or stamp banner (it hides the field's buttons, so no new world would ever be seen)
            if (!faded && ("obtained" in all || ("stamp" in all && ("you got" in all || "filled out" in all)))) {
                if ("obtained" in all) { noteItem(r, lines); r.chests++; ctx.count("chests") } else r.stamps++
                MacroLog.i("World ${r.world}: a late banner: ${lines.joinToString(" | ") { it.text }.take(80)}")
                dismiss(ctx)
                return Taken.SOME
            }
            if (dark(ctx)) faded = true
            else if (faded) {
                // the new world's title banner shows (and hides the field's buttons) right after the fade
                val title = lines.filter { it.center.y < 0.3 && it.center.x in 0.1..0.9 && Ocr.norm(it.text) != "am" }.joinToString(" ") { it.text.trim() }
                if (Ocr.norm(title).length > Ocr.norm(r.nextTitle).length) r.nextTitle = title
            }
            if (faded && !dark(ctx) && ctx.find("field_menu_button") != null) {
                MacroLog.i("a new world")
                ctx.wait(1.0)
                return Taken.LEFT
            }
            if (!faded && ctx.find("field_menu_button") != null && System.currentTimeMillis() > end - 20_000) return Taken.NONE
            ctx.wait(0.7)
        }
        return Taken.NONE
    }

    /** Congratulations → autosave → the Another Dungeon map: tap through and close it. */
    private fun finish(r: Run): String {
        val ctx = r.ctx
        repeat(20) {
            val lines = ctx.readText()
            if (lines.any { Ocr.norm(it.text) == "another dungeon" } && lines.any { Ocr.norm(it.text) == "hollow" }) {
                ctx.tapExact(AD_CLOSE); ctx.wait(2.0)
                return "Phantom Crystal Dimension done"
            }
            ctx.refresh()
            if (ctx.find("field_menu_button") != null && lines.none { Ocr.norm(it.text).contains("congratulations") }) return "Phantom Crystal Dimension done"
            ctx.tap(Point(0.5, 0.25)); ctx.wait(2.0)
        }
        return "Phantom Crystal Dimension: finished, but stuck on the screens after it"
    }

    // --- helpers ------------------------------------------------------------------------
    private fun noteItem(r: Run, lines: List<Ocr.Line>) {
        // OCR can split the count off the name ("Obtained <item>" | "x3."): join them back
        val texts = mutableListOf<String>()
        for (l in lines) {
            // ...or the word "Obtained" off its item ("Obtained" | "High Class Scroll x2.", 2026-11-23)
            if (texts.isNotEmpty() && Ocr.norm(texts.last()) == "obtained") texts[texts.size - 1] = texts.last() + " " + l.text
            else if (texts.isNotEmpty() && Regex("""^\s*[x×X]\s*[0-9OoIl]""").containsMatchIn(l.text) &&
                !Regex("""[x×X]\s*[0-9OoIl,]+\.?\s*$""").containsMatchIn(texts.last())) texts[texts.size - 1] = texts.last() + " " + l.text
            else texts += l.text
        }
        for (t in texts) {
            Regex("""^\s*([0-9,OoIl]+)\s*Git obtained""").find(t)?.let { m ->
                m.groupValues[1].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull()?.let { r.items["Git"] = (r.items["Git"] ?: 0) + it }
            }
            Regex("""^\s*Obtained\s*([A-Za-z'.\- ]+?)\s*[x×X]\s*([0-9OoIl,]+)""").find(t)?.let { m ->
                val n = m.groupValues[2].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull() ?: return@let
                val name = m.groupValues[1].trim()
                r.items[name] = (r.items[name] ?: 0) + n
                if (name.contains("Broken Club") || name.contains("Medium Class")) r.cape = true
            }
        }
    }

    /** An item or stamp banner at the top of the screen. */
    private fun bannerOpen(ctx: Context): Boolean {
        val top = ctx.readText(Region(0.15, 0.0, 0.7, 0.25)).joinToString(" ") { Ocr.norm(it.text) }
        return "obtained" in top || "stamp" in top
    }

    // the item and stamp popups are a banner at the top: a tap on the banner closes it (a tap low on
    // the screen can leave it open)
    private fun dismiss(ctx: Context) { MacroLog.i("closing the banner"); ctx.tap(Point(0.5, 0.11)); ctx.wait(1.2) }

    /** Close a banner by tapping the banner itself (a tap on the field would walk the party). */
    private fun dismissBanner(ctx: Context) { ctx.tap(Point(0.5, 0.11)); ctx.wait(2.0) }

    private fun walk(ctx: Context, dir: Double, ms: Long) {
        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), ms)
        ctx.wait(0.5)
    }

    /** A hold of [ms] from rest, counted for the draft route. */
    private fun walk(r: Run, dir: Double, ms: Long) {
        walk(r.ctx, dir, ms)
        r.pos += dir * r.walkTable.distance(ms.toDouble())
    }

    /** A short look-around step: false when the party didn't move (a wall); only a move counts for the draft route. */
    private fun stride(r: Run, dir: Double, ms: Long): Boolean {
        var before = view(r.ctx)
        walk(r.ctx, dir, ms)
        var moved = moved(r.ctx, before)
        // an item or stamp banner left open holds the party still: close it and step again (2026-11-25: most likely
        // a stamp banner made three steps "walls" in a row, and the run stopped with "no way out")
        if (!moved && bannerOpen(r.ctx)) {
            MacroLog.i("World ${r.world}: a banner held the party: closing it")
            dismiss(r.ctx)
            before = view(r.ctx)
            walk(r.ctx, dir, ms)
            moved = moved(r.ctx, before)
        }
        if (moved) r.pos += dir * r.walkTable.distance(ms.toDouble())
        return moved
    }

    /** A draft route step: the walk since the last one (as one hold from rest), then [kind] at the "!" [b]. */
    private fun note(r: Run, kind: String, b: Point) {
        val d = r.pos - r.mark
        if (abs(d) > r.walkTable.minWalk) r.draft += "${if (d > 0) "R" else "L"} ${(r.walkTable.holdMs(abs(d)) / 50).roundToLong() * 50}"
        r.draft += "$kind ${"%.2f".format(java.util.Locale.US, b.x)} ${"%.2f".format(java.util.Locale.US, b.y)}"
        r.mark = r.pos
    }

    /** A swept world's draft route, ready for the macro's `worlds:` data (walls make it approximate: check it on the phone). */
    private fun logDraft(r: Run) {
        if (r.routed || r.draft.isEmpty()) return
        MacroLog.i("route draft for world ${r.world}: {title: \"${worldName(r.title)}\", route: [${r.draft.joinToString(", ")}]}")
    }

    private fun dark(ctx: Context): Boolean {
        val g = ctx.frame?.gray ?: return false
        val m = Core.mean(g).`val`[0]
        return m < 18 || m > 240 // the fade between worlds: black or white
    }

    private fun view(ctx: Context): Mat {
        val g = ctx.refresh().gray
        val small = Mat()
        Imgproc.resize(g.submat(0, (g.rows() * 0.8).toInt(), 0, (g.cols() * 0.85).toInt()), small, Size(120.0, 54.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return small
    }

    private fun moved(ctx: Context, before: Mat): Boolean {
        val after = view(ctx)
        val d = Mat(); Core.absdiff(before, after, d)
        val m = Core.mean(d).`val`[0]
        listOf(before, after, d).forEach { it.release() }
        return m > 3.0
    }

    private val cache = HashMap<String, Mat>()

    private fun icon(ctx: Context, name: String): Mat = cache.getOrPut(name) {
        runCatching { ctx.assets.open("$ICONS$name.png").use { it.readBytes() } }.getOrNull()
            ?.let { Imgcodecs.imdecode(MatOfByte(*it), Imgcodecs.IMREAD_GRAYSCALE) } ?: Mat()
    }

    /** Every place [t] shows on [g], over several sizes (the worlds are drawn at different zooms). */
    private fun findAll(full: Mat, t: Mat, scales: List<Double>, min: Double): List<Point> {
        if (t.empty()) return emptyList()
        // at half size: full size takes seconds per look
        val g = Mat(); Imgproc.resize(full, g, Size(0.0, 0.0), HALF, HALF, Imgproc.INTER_AREA)
        val hits = ArrayList<Pair<Double, Point>>()
        for (k in scales) {
            val s = Mat(); Imgproc.resize(t, s, Size(0.0, 0.0), k * HALF, k * HALF, Imgproc.INTER_AREA)
            if (s.rows() >= g.rows() || s.cols() >= g.cols()) { s.release(); continue }
            val res = Mat(); Imgproc.matchTemplate(g, s, res, Imgproc.TM_CCOEFF_NORMED)
            repeat(10) {
                val mm = Core.minMaxLoc(res)
                if (mm.maxVal < min) return@repeat
                hits += mm.maxVal to Point((mm.maxLoc.x + s.cols() / 2.0) / g.cols(), (mm.maxLoc.y + s.rows() / 2.0) / g.rows())
                Imgproc.rectangle(res, org.opencv.core.Point(mm.maxLoc.x - s.cols() / 2.0, mm.maxLoc.y - s.rows() / 2.0),
                    org.opencv.core.Point(mm.maxLoc.x + s.cols() / 2.0, mm.maxLoc.y + s.rows() / 2.0), Scalar(-1.0), -1)
            }
            s.release(); res.release()
        }
        g.release()
        val out = ArrayList<Point>()
        for ((_, p) in hits.sortedByDescending { it.first }) if (out.none { abs(it.x - p.x) < 0.02 && abs(it.y - p.y) < 0.03 }) out += p
        return out
    }

    private const val HALF = 0.5

    /** A cat's "!" sits at y ~0.29, a person's higher (smaller y): a still "!" lower on screen than this is taken for a cat. */
    private const val CAT_MIN_Y = 0.275

    /**
     * Above this a "!" is the stairs out (stairs ~0.234, the Sensationalist ~0.26, the cat ~0.30).
     */
    private const val STAIRS_MAX_Y = 0.248

    /** The "!" bubbles on screen (not under the app's own buttons on the right). */
    private fun bubbles(ctx: Context, f: Frame): List<Point> =
        findAll(f.gray, icon(ctx, "field_interact_bubble"), listOf(0.8, 0.9, 1.0, 1.2, 1.35), 0.7).filter { it.x < 0.92 }

    /**
     * Closed treasure chests in view: the red and gold kind and the blue-grey metal kind (the red
     * template doesn't match the metal one).
     */
    private fun closedChests(ctx: Context): List<Point> {
        val g = ctx.frame?.gray ?: ctx.refresh().gray
        val all = findAll(g, icon(ctx, "closed_chest"), listOf(0.7, 0.8, 0.9, 1.0), 0.68) +
            findAll(g, icon(ctx, "closed_chest_blue"), listOf(0.7, 0.8, 0.9, 1.0), 0.75)
        return all.filter { it.y > 0.3 }
    }

    private fun waitForWorld(ctx: Context, seconds: Int): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null && !dark(ctx)) return true
            ctx.wait(1.0)
        }
        return false
    }
}
