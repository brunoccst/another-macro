package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The Phantom Crystal Dimension (anothereden.wiki/w/Phantom_Crystal_Dimension), with the White
 * Key a finished Another Dungeon run can drop (10%):
 *
 *  1. the "!" over the Another Dungeon door → the glowing "???" tab → "Phantom Crystal
 *     Dimension" → Hard ("Using White Key×1") → Move;
 *  2. every world: open every chest (each shows a "!" when the party is close), tap the cats
 *     (a paw stamp each), then take a portal (any other "!": the outcome was decided when the
 *     key dropped, so which one doesn't matter). World I has no chests; its king cat and
 *     notice board open the paw stamp menu, which is cancelled (claiming is left to you);
 *  3. bonus worlds (Ratle shop, Nuaru Uplands, Izana) move on by themselves once emptied;
 *  4. the Cape or the final world: its chests, then the "!" on the stairs out; Congratulations;
 *  5. an NPC (final world, sometimes the Cape) offers a free Treatise/Codex/Opus of
 *     your choice: the run stops there for you to choose.
 *
 * There are no enemies and no area map. Nothing is bought: every tap goes through the same
 * purchase guard as the rest of the app.
 */
object PhantomCrystal {
    private const val ICONS = "templates/another_dungeon/"
    private val AD_CLOSE = Point(0.88, 0.03)
    private val PHANTOM_TAB = Point(0.375, 0.165)

    private class Run(val ctx: Context) {
        val start = System.currentTimeMillis()
        var world = 1
        val items = LinkedHashMap<String, Int>()
        var chests = 0
        var stamps = 0
        var cape = false
        /** "!" spots that open a menu (the stamp cat, the notice board): screen x in the current world. */
        val skip = ArrayList<Point>()
    }

    fun run(ctx: Context): String {
        val r = Run(ctx)
        val outcome = try { inner(r) } catch (s: StopScenario) { s.message ?: "stopped" }
        val secs = (System.currentTimeMillis() - r.start) / 1000
        val items = r.items.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val text = "$outcome — ${secs / 60}m ${secs % 60}s; worlds ${r.world}${if (r.cape) " (ended in the Cape)" else ""}; " +
            "chests ${r.chests}, stamps ${r.stamps}; items: $items"
        runCatching {
            val o = org.json.JSONObject().put("when", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.start)))
                .put("dungeon", "Phantom Crystal Dimension").put("seconds", secs).put("outcome", outcome).put("worlds", r.world)
                .put("cape", r.cape).put("chests", r.chests).put("stamps", r.stamps).put("items", org.json.JSONObject(r.items as Map<*, *>))
            java.io.File(ctx.debugDir.parentFile, "runs.log").appendText(o.toString() + "\n")
        }.onFailure { MacroLog.e("could not save the run report", it) }
        MacroLog.status(text)
        return text
    }

    private fun inner(r: Run): String {
        val ctx = r.ctx
        if (!inside(ctx)) enter(r)
        while (true) {
            MacroLog.status("Phantom Crystal Dimension: world ${r.world}${if (r.cape) " (the Cape)" else ""}")
            when (clearWorld(r)) {
                Next.WORLD -> { r.world++; r.skip.clear() }
                Next.END -> return finish(r)
            }
        }
    }

    private enum class Next { WORLD, END }

    // --- 1. entering ------------------------------------------------------------------
    /** Inside already: a field whose bottom bar has only Menu and Rewards (outside it has Quests, Tomes, Diary, Map). */
    private fun inside(ctx: Context): Boolean {
        ctx.refresh()
        if (ctx.find("field_menu_button") == null) return false
        val bar = ctx.readText(Region(0.0, 0.82, 0.6, 0.18)).map { Ocr.norm(it.text) }
        return "rewards" in bar && bar.none { it == "quests" || it == "map" || it == "diary" }
    }

    private fun enter(r: Run) {
        val ctx = r.ctx
        MacroLog.status("Entering the Phantom Crystal Dimension")
        ctx.refresh()
        val door = ctx.find("field_interact_bubble") ?: ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift)")
        ctx.tap(door); ctx.wait(2.5)
        var opened = false
        for (i in 0 until 6) {
            if (ctx.readText().any { Ocr.norm(it.text) == "another dungeon" }) { opened = true; break }
            ctx.wait(1.0)
        }
        if (!opened) ctx.stop("the door didn't open the Another Dungeon map")
        ctx.tapExact(PHANTOM_TAB); ctx.wait(2.0)
        val label = ctx.readText().firstOrNull { Ocr.similarity(it.text, "Phantom Crystal") >= 0.7 || Ocr.norm(it.text).startsWith("phantom crystal") }
        if (label == null) {
            ctx.tapExact(AD_CLOSE); ctx.wait(1.5)
            ctx.stop("no White Key: the Phantom Crystal Dimension isn't open")
        }
        ctx.tapExact(label.center); ctx.wait(2.0)
        val hard = ctx.readText().firstOrNull { Ocr.norm(it.text) == "hard" && it.center.x > 0.6 } ?: ctx.stop("no Hard entry for the Phantom Crystal Dimension")
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
            if (chest != null && abs(chest.x - 0.5) > 0.05) {
                walk(ctx, if (chest.x < 0.5) -1.0 else 1.0, 350)
                continue
            }
            val before = view(ctx)
            walk(ctx, dir, 700)
            if (!moved(ctx, before)) { ends++; dir = -dir }
        }
        // nothing left to take: a portal (or, in the Cape / final world, the stairs out)
        return leave(r)
    }

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
        for (b in bubbles) {
            if (r.skip.any { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 }) continue
            Thread.sleep(500)
            val again = bubbles(ctx, ctx.refresh())
            val moved = again.none { abs(it.x - b.x) < 0.006 && abs(it.y - b.y) < 0.01 } && again.any { abs(it.x - b.x) < 0.08 && abs(it.y - b.y) < 0.05 }
            if (moved) return tapBubble(r, again.minByOrNull { abs(it.x - b.x) }!!)
        }
        return Taken.NONE
    }

    /** Tap a "!" and see what it was: an item, a stamp, a menu, or a way out. */
    private fun tapBubble(r: Run, b: Point): Taken {
        val ctx = r.ctx
        ctx.tapExact(b); ctx.wait(2.0)
        val lines = ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        when {
            "treatise" in all || "codex" in all || "opus" in all -> ctx.stop("an NPC offers a free Treatise/Codex/Opus: choose it yourself")
            "stamp" in all && ("you got" in all || "filled out" in all) -> {
                r.stamps++; MacroLog.i("paw stamp"); dismiss(ctx); return Taken.SOME
            }
            "obtained" in all -> {
                noteItem(r, lines); r.chests++; ctx.count("chests"); dismiss(ctx); return Taken.SOME
            }
            "what would you like to do" in all || lines.any { Ocr.norm(it.text) == "cancel" } -> {
                // the paw stamp menu (king cat / notice board): leave it, and don't tap here again
                lines.firstOrNull { Ocr.norm(it.text) == "cancel" }?.let { ctx.tapExact(it.center) }
                r.skip += b
                ctx.wait(1.5)
                return Taken.SOME
            }
        }
        return afterLeaving(r)
    }

    /** Take any "!" left (not a known menu): a portal, the stairs out. */
    private fun leave(r: Run): Next {
        val ctx = r.ctx
        // the unfolded floating buttons' label can sit over a "!" near the right edge and hide it from the match
        if (InputService.instance?.controls(true)?.first == true) Thread.sleep(400)
        repeat(3) {
            val left = bubbles(ctx, ctx.refresh()).filter { b -> r.skip.none { abs(it.x - b.x) < 0.03 && abs(it.y - b.y) < 0.05 } }
            for (b in left.sortedBy { abs(it.x - 0.5) }) {
                MacroLog.status("World ${r.world}: taking the way on")
                when (tapBubble(r, b)) {
                    Taken.LEFT -> return Next.WORLD
                    Taken.END -> return Next.END
                    else -> {}
                }
            }
            // none in view: walk the world once more to find one
            walk(ctx, if (it % 2 == 0) 1.0 else -1.0, 2500)
        }
        ctx.stop("no way out of world ${r.world} found")
    }

    /** After a tap that wasn't an item/stamp/menu: did we leave the world (or the dimension)? */
    private fun afterLeaving(r: Run): Taken {
        val ctx = r.ctx
        val end = System.currentTimeMillis() + 25_000
        var faded = false
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            if ("congratulations" in all || "obtained dungeon items" in all) return Taken.END
            if (lines.any { Ocr.norm(it.text) == "another dungeon" }) return Taken.END
            if (dark(ctx)) faded = true
            else if (faded && ctx.find("field_menu_button") != null) {
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
        for (l in lines) {
            val t = l.text
            Regex("""^\s*([0-9,OoIl]+)\s*Git obtained""").find(t)?.let { m ->
                m.groupValues[1].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull()?.let { r.items["Git"] = (r.items["Git"] ?: 0) + it }
            }
            Regex("""^\s*Obtained\s+([A-Za-z'.\- ]+?)\s*[x×X]\s*([0-9OoIl,]+)""").find(t)?.let { m ->
                val n = m.groupValues[2].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull() ?: return@let
                val name = m.groupValues[1].trim()
                r.items[name] = (r.items[name] ?: 0) + n
                if (name.contains("Broken Club") || name.contains("Medium Class")) r.cape = true
            }
        }
    }

    private fun dismiss(ctx: Context) { ctx.tap(Point(0.5, 0.75)); ctx.wait(1.2) }

    private fun walk(ctx: Context, dir: Double, ms: Long) {
        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), ms)
        ctx.wait(0.5)
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
    private fun findAll(g: Mat, t: Mat, scales: List<Double>, min: Double): List<Point> {
        if (t.empty()) return emptyList()
        val hits = ArrayList<Pair<Double, Point>>()
        for (k in scales) {
            val s = Mat(); Imgproc.resize(t, s, Size(0.0, 0.0), k, k)
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
        val out = ArrayList<Point>()
        for ((_, p) in hits.sortedByDescending { it.first }) if (out.none { abs(it.x - p.x) < 0.02 && abs(it.y - p.y) < 0.03 }) out += p
        return out
    }

    /** The "!" bubbles on screen (not under the app's own buttons on the right). */
    private fun bubbles(ctx: Context, f: Frame): List<Point> =
        findAll(f.gray, icon(ctx, "field_interact_bubble"), listOf(0.8, 0.9, 1.0, 1.2, 1.35), 0.7).filter { it.x < 0.92 }

    /** Closed treasure chests in view (the red and gold kind every world here uses). */
    private fun closedChests(ctx: Context): List<Point> =
        findAll(ctx.frame?.gray ?: ctx.refresh().gray, icon(ctx, "closed_chest"), listOf(0.7, 0.8, 0.9, 1.0), 0.68).filter { it.y > 0.3 }

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
