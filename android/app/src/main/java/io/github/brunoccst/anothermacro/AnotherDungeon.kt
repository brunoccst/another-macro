package io.github.brunoccst.anothermacro

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * One run of an Another Dungeon (data in templates/another_dungeon/dungeons.yaml):
 *
 *  1. enter: the "!" over the Another Dungeon door (Spacetime Rift) → era tab → the
 *     dungeon's label → Hard (green key) or Very Hard (red key) → Move, or Skip when a
 *     Cat Express Ticket should be used (skips the whole dungeon);
 *  2. every map: walk its known layout (the area map hides unexplored parts) to each
 *     chest and open it with the "!" bubble, fighting the encounters on the way;
 *  3. "fight all enemies": after a map's chests, walk back and forth fighting until the
 *     game says no enemies are left (or none come any more);
 *  4. walk to the map's exit and take it;
 *  5. the last map (no layout yet): sweep it, tapping every "!" (its chest, the boss);
 *     the boss battle gets the boss skill setup when one is chosen;
 *  6. stop when the run ends, and say so when a White Key opened the Phantom Crystal Dimension.
 *
 * Nothing that costs anything is ever confirmed: the key counters' "+" are key purchases
 * and are never tapped, and any purchase offer stops the run.
 */
object AnotherDungeon {
    data class MapSpec(val names: List<String>, val layout: AreaNav.Layout?, val chests: List<Point>, val exit: Point?, val boss: Boolean)
    data class Spec(val id: String, val name: String, val era: String, val boss: String, val maps: List<MapSpec>)

    private const val DATA = "templates/another_dungeon/dungeons.yaml"
    private const val BUBBLE = "field_interact_bubble"

    /** Words that mean the game wants something bought: never tap on, cancel and stop. */
    private val PURCHASE_WORDS = listOf("purchase", "chronos stone", "tsubura", "insufficient", "not enough")

    @Suppress("UNCHECKED_CAST")
    fun spec(ctx: Context, id: String): Spec {
        val text = ctx.assets.open(DATA).bufferedReader().use { it.readText() }
        val all = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Map<String, Any?>>
        val d = all[id] ?: throw IllegalArgumentException("unknown Another Dungeon '$id' (known: ${all.keys.joinToString()})")
        fun nums(v: Any?) = (v as? List<*>)?.map { (it as Number).toDouble() }
        fun lists(v: Any?) = (v as? List<*>)?.mapNotNull { nums(it) } ?: emptyList()
        val maps = (d["maps"] as List<Map<String, Any?>>).map { m ->
            val segs = lists(m["segs"]).map { AreaNav.Seg(it[0], it[1], it[2]) }
            val links = lists(m["links"]).map { AreaNav.Link(it[0], it[1], it[2]) }
            MapSpec(
                (m["names"] as List<*>).map { it.toString() },
                if (segs.isEmpty()) null else AreaNav.Layout(segs, links),
                lists(m["chests"]).map { Point(it[0], it[1]) },
                nums(m["exit"])?.let { Point(it[0], it[1]) },
                m["boss"] == true,
            )
        }
        return Spec(id, d["name"].toString(), d["era"].toString(), d["boss"]?.toString() ?: "", maps)
    }

    private class Run(val ctx: Context, val spec: Spec) {
        val mobs = Battle.Setup.from(ctx.params, "skill_")
        val bossSetup = if (ctx.bool("boss_setup")) Battle.Setup.from(ctx.params, "boss_skill_") else mobs
        val fightAll = ctx.bool("fight_all")
        var map = -1
        var chestsDone = 0
        var cleared = false
        var onBossMap = false
        var bossBeaten = false
        var phantom = false
        var entered = false
    }

    fun run(ctx: Context, id: String): String {
        val spec = spec(ctx, id)
        val r = Run(ctx, spec)
        MacroLog.i("${spec.name}: key ${ctx.str("key")}, cat ticket ${ctx.bool("cat_ticket")}, " +
            "fight all ${r.fightAll}, mobs ${r.mobs}, boss ${r.bossSetup}")
        if (whereAmI(r) == null) {
            enter(r)?.let { return it }
        }
        repeat(300) {
            if (r.phantom) return phantomDone(r)
            val idx = whereAmI(r)
            if (idx == null) {
                if (fight(r)) return@repeat
                // not on one of the dungeon's maps any more: the run ended
                return finish(r)
            }
            if (idx != r.map) {
                r.map = idx; r.chestsDone = 0; r.cleared = false
                r.onBossMap = spec.maps[idx].boss
                MacroLog.i("map ${idx + 1}/${spec.maps.size}: ${spec.maps[idx].names.first()}")
                ctx.count("maps")
            }
            val ms = spec.maps[idx]
            when {
                ms.layout == null -> sweep(r)
                r.chestsDone < ms.chests.size -> takeChest(r, ms, ms.chests[r.chestsDone])
                r.fightAll && !r.cleared -> grind(r, ms)
                ms.exit != null -> leave(r, ms, idx)
                else -> sweep(r)
            }
            if (r.bossBeaten) return finish(r)
        }
        return "${spec.name}: gave up after 300 steps"
    }

    private fun phantomDone(r: Run) = "${r.spec.name} done: the Phantom Crystal Dimension is open (White Key)"

    // --- 1. entering ------------------------------------------------------------------
    /** Through the Another Dungeon door. A message when the run ends here (skipped), else null. */
    private fun enter(r: Run): String? {
        val ctx = r.ctx
        val red = ctx.str("key").lowercase() == "red"
        val difficulty = if (red) "Very Hard" else "Hard"
        if (!waitForField(ctx)) ctx.stop("not on the field: start next to the Another Dungeon door in the Spacetime Rift")
        val door = ctx.find(BUBBLE) ?: ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift)")
        ctx.tap(door); ctx.wait(2.5)
        if (ctx.readText().none { Ocr.norm(it.text) == "another dungeon" }) ctx.stop("the door didn't open the Another Dungeon map: ${seen(ctx)}")
        tapText(r, eraLabel(r.spec.era)); ctx.wait(1.5)
        tapText(r, r.spec.name, minY = 0.2, maxX = 0.62) ?: ctx.stop("${r.spec.name} not on the ${r.spec.era} map: ${seen(ctx)}")
        ctx.wait(2.0)
        tapText(r, difficulty, exact = true, minX = 0.6) ?: ctx.stop("no '$difficulty' entry for ${r.spec.name}: ${seen(ctx)}")
        ctx.wait(2.0)
        // "Using Green Key×1 to move to Dogma Tower (Hard)."
        val lines = ctx.readText()
        val using = lines.firstOrNull { Ocr.norm(it.text).startsWith("using") }?.text ?: ""
        val keyWord = if (red) "red" else "green"
        if (!Ocr.norm(using).contains(keyWord)) {
            tapText(r, "Back", exact = true)
            ctx.stop("expected a $keyWord key for $difficulty but the game says: '$using'")
        }
        if (ctx.bool("cat_ticket")) {
            tapText(r, "Skip", exact = true) ?: ctx.stop("no Skip button (Cat Express Ticket): ${seen(ctx)}")
            ctx.wait(2.0)
            // the skip dialog: confirm only (a purchase offer stops in tapText)
            tapText(r, "OK", "Yes", "Skip", "Use", exact = true, minY = 0.45) ?: ctx.stop("couldn't confirm the Skip: ${seen(ctx)}")
            ctx.count("cat_tickets")
            ctx.wait(4.0)
            return finish(r)
        }
        tapText(r, "Move", exact = true) ?: ctx.stop("no Move button: ${seen(ctx)}")
        ctx.count("entered")
        r.entered = true
        ctx.wait(6.0)
        // popups on arrival (the daily ad offer, notices): take what's free and close the rest
        repeat(8) {
            if (waitForField(ctx, 4)) return null
            clearPopup(r)
        }
        if (!waitForField(ctx, 20)) ctx.stop("didn't arrive in ${r.spec.name}: ${seen(ctx)}")
        return null
    }

    private fun eraLabel(era: String) = when (era.lowercase()) { "present" -> "Pres."; "antiquity" -> "Antiq."; "future" -> "Fut."; else -> era }

    /** A dialog over the field: the free daily ad ("Watch Video"), thank-you / OK / Close. */
    private fun clearPopup(r: Run) {
        tapText(r, "Watch Video", "Close", "OK", exact = true, tries = 1)
        r.ctx.wait(3.0)
    }

    // --- 2-4. inside --------------------------------------------------------------------
    /** Which of the dungeon's maps the area map names (its title "Dogma Tower - <map>"), or null. */
    private fun whereAmI(r: Run): Int? {
        val ctx = r.ctx
        if (!waitForField(ctx, 10)) return null
        ctx.tap(AreaNav.MINIMAP); ctx.wait(2.0)
        val text = ctx.readText()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.5)
        val title = text.filter { it.center.y < 0.12 }.joinToString(" ") { it.text }
        if (!Ocr.norm(title).contains(Ocr.norm(r.spec.name))) return null
        val part = title.substringAfter("-").trim()
        val best = r.spec.maps.indices.maxByOrNull { i -> r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) } } ?: return null
        return if (r.spec.maps[best].names.maxOf { Ocr.similarity(part, it) } > 0.6) best else null
    }

    /** Fight what interrupted a walk; true if there was a battle. */
    private fun onStep(r: Run): () -> Boolean = { fight(r) }

    private fun takeChest(r: Run, ms: MapSpec, chest: Point) {
        val ctx = r.ctx
        MacroLog.i("chest ${r.chestsDone + 1}/${ms.chests.size} at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)})")
        AreaNav.walkLayout(ctx, ms.layout!!, chest, onStep(r))
        // close by: the chest's "!" shows; step toward it until it does
        val party = AreaNav.look(ctx) { emptyList() }.party
        val dir = if (party != null && chest.x < party.x) -1.0 else 1.0
        for (nudge in 0..5) {
            if (openBubble(r)) { r.chestsDone++; ctx.count("chests"); return }
            AreaNav.hold(ctx, if (nudge < 4) dir else -dir, 0.012)
            fight(r)
        }
        MacroLog.i("no chest found by (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}); skipping it")
        r.chestsDone++
    }

    /** Tap a "!" bubble on screen and clear what it opens (a chest's "Obtained ..."). True if there was one. */
    private fun openBubble(r: Run): Boolean {
        val ctx = r.ctx
        ctx.refresh()
        val b = ctx.find(BUBBLE) ?: return false
        ctx.tap(b); ctx.wait(2.0)
        val text = ctx.readText().joinToString(" ") { it.text }
        MacroLog.i("\"!\": ${text.take(120)}")
        if (fight(r)) return true // the boss (or a guard) behind it
        ctx.tap(Point(0.5, 0.75)); ctx.wait(1.2)
        waitForField(ctx, 6)
        return true
    }

    /** Walk to the map's exit and take its ground arrow (tap it); flick as a fallback. */
    private fun leave(r: Run, ms: MapSpec, idx: Int) {
        val ctx = r.ctx
        val exit = ms.exit!!
        MacroLog.i("to the exit at (${"%.3f".format(exit.x)}, ${"%.3f".format(exit.y)})")
        AreaNav.walkLayout(ctx, ms.layout!!, exit, onStep(r))
        // the arrow shows on the ground once the party is near; walk on toward the exit until it does
        val toward = if (exit.x > 0.5) 1.0 else -1.0
        for (attempt in 0..7) {
            ctx.refresh()
            val arrow = ARROWS.mapNotNull { ctx.find(it) }.minByOrNull { abs(it.center.x - 0.5) }
            if (arrow != null) {
                MacroLog.i("taking the ground arrow at (${"%.2f".format(arrow.center.x)}, ${"%.2f".format(arrow.center.y)})")
                ctx.tap(arrow); ctx.wait(4.0)
                fight(r)
                if (whereAmI(r) != idx) return
            }
            when (attempt) {
                3 -> AreaNav.flick(ctx, up = false)
                5 -> AreaNav.flick(ctx, up = true)
                else -> AreaNav.hold(ctx, if (attempt < 3) -toward else toward, 0.008)
            }
            fight(r)
        }
        MacroLog.i("the exit didn't take us anywhere yet")
    }

    /** The round signs on the ground where a staircase/door leads to another map. */
    private val ARROWS = listOf("ground_arrow_bars", "ground_arrow_steps")

    /** Walk back and forth fighting until the game says no enemies are left (or none come any more). */
    private fun grind(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        var dir = 1.0
        var quiet = 0
        while (quiet < 12) {
            AreaNav.hold(ctx, dir, 0.05)
            dir = -dir
            if (fight(r)) quiet = 0 else quiet++
            if (noEnemiesLeft(ctx)) { MacroLog.i("no enemies left"); r.cleared = true; return }
        }
        MacroLog.i("no encounter in $quiet walks: taking ${ms.names.first()} as cleared")
        r.cleared = true
    }

    private fun noEnemiesLeft(ctx: Context): Boolean {
        val all = ctx.readText().joinToString(" ") { Ocr.norm(it.text) }
        return ("enem" in all || "monster" in all) && (" no " in " $all " || "defeated all" in all || "all enemies" in all || "none" in all)
    }

    /**
     * A map without a known layout (the boss's): walk to one end, then to the other in
     * short steps, tapping every "!" (chests, the boss); then try the streets above/below.
     */
    private fun sweep(r: Run) {
        val ctx = r.ctx
        if (r.fightAll && !r.cleared && !r.onBossMap) { grind(r, r.spec.maps[r.map]); return }
        for (vertical in listOf(null, true, false)) {
            if (vertical != null) { AreaNav.flick(ctx, vertical); fight(r); if (r.bossBeaten) return }
            for (dir in listOf(-1.0, 1.0)) {
                var still = 0
                var last: Point? = null
                for (step in 0 until 30) {
                    if (openBubble(r)) ctx.count("bubbles")
                    if (r.bossBeaten) return
                    AreaNav.hold(ctx, dir, 0.025)
                    fight(r)
                    if (r.bossBeaten) return
                    val p = AreaNav.look(ctx) { emptyList() }.party
                    val prev = last
                    if (p != null && prev != null && abs(p.x - prev.x) < 0.004) { if (++still >= 2) break } else still = 0
                    last = p
                }
            }
        }
    }

    /** Fight a battle if one is on screen: boss setup for the boss. True if there was one. */
    private fun fight(r: Run): Boolean {
        val ctx = r.ctx
        var wasBoss = false
        val out = Battle.fight(ctx) { c ->
            // the enemies' names show while a skill list is open: look on the boss's map
            if (r.onBossMap && r.spec.boss.isNotEmpty()) {
                val names = Battle.enemyNames(c)
                wasBoss = Ocr.norm(names).contains(Ocr.norm(r.spec.boss))
                MacroLog.i("enemies: ${names.take(80)}${if (wasBoss) " (boss)" else ""}")
            }
            if (wasBoss) r.bossSetup else r.mobs
        }
        when (out) {
            Battle.Outcome.LOST -> ctx.stop("party was defeated in ${r.spec.name}")
            Battle.Outcome.NONE -> return false
            Battle.Outcome.WON -> {
                ctx.count("battles")
                if (wasBoss) { r.bossBeaten = true; ctx.count("bosses") }
                checkPhantom(r)
            }
        }
        return true
    }

    // --- 6. the end ------------------------------------------------------------------
    /** Tap through the completion screens, noting a White Key / Phantom Crystal Dimension. */
    private fun finish(r: Run): String {
        val ctx = r.ctx
        repeat(25) {
            checkPhantom(r)
            if (r.phantom) return phantomDone(r)
            ctx.refresh()
            if (ctx.find("field_menu_button") != null && whereAmI(r) == null) return "${r.spec.name} done"
            if (ctx.find("field_menu_button") != null) return "${r.spec.name}: the boss is beaten but the party is still inside"
            if (Battle.inBattle(ctx)) { fight(r); return@repeat }
            tapText(r, "OK", "Close", "Next", "Leave", "Exit", exact = true, tries = 1) ?: ctx.tap(Point(0.5, 0.8))
            ctx.wait(2.0)
        }
        return "${r.spec.name}: finished, but stuck on the screens after it (${seen(ctx)})"
    }

    private fun checkPhantom(r: Run) {
        val all = r.ctx.readText().joinToString(" ") { Ocr.norm(it.text) }
        if ("white key" in all || "phantom crystal" in all) {
            MacroLog.i("White Key: the Phantom Crystal Dimension is open")
            r.phantom = true
        }
    }

    // --- helpers ---------------------------------------------------------------------
    /** Tap the first on-screen text matching one of [wants]; never on a purchase offer, never a "+". */
    private fun tapText(
        r: Run, vararg wants: String, exact: Boolean = false, tries: Int = 3,
        minY: Double = 0.0, minX: Double = 0.0, maxX: Double = 1.0,
    ): Ocr.Line? {
        val ctx = r.ctx
        repeat(tries) {
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            PURCHASE_WORDS.firstOrNull { it in all }?.let { w ->
                lines.firstOrNull { Ocr.norm(it.text) in setOf("cancel", "no", "close", "back") }?.let { ctx.tapExact(it.center) }
                ctx.stop("safety stop: the game offered a purchase ('$w'); nothing bought")
            }
            for (want in wants) {
                val n = Ocr.norm(want)
                val hit = lines.firstOrNull { l ->
                    val t = Ocr.norm(l.text)
                    l.center.y >= minY && l.center.x in minX..maxX && t.isNotEmpty() &&
                        (if (exact) t == n else t == n || t.contains(n) || Ocr.similarity(l.text, want) >= 0.8)
                }
                if (hit != null) { ctx.tapExact(hit.center); return hit }
            }
            if (it < tries - 1) ctx.wait(1.0)
        }
        return null
    }

    private fun seen(ctx: Context) = ctx.readText().joinToString(" | ") { it.text }.take(300)

    private fun waitForField(ctx: Context, seconds: Int = 20): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) return true
            if (Battle.inBattle(ctx)) return false
            ctx.wait(1.0)
        }
        return false
    }
}
