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
    data class MapSpec(val names: List<String>, val layout: AreaNav.Layout?, val chests: List<Point>, val exit: Point?, val boss: Boolean, val arrival: Point? = null, val chestsCount: Int = 0) {
        /** The same map drawn [dx] further right. */
        fun shifted(dx: Double) = copy(
            layout = layout?.let { l -> AreaNav.Layout(l.segs.map { it.copy(x1 = it.x1 + dx, x2 = it.x2 + dx) }, l.links.map { it.copy(x = it.x + dx) }) },
            chests = chests.map { Point(it.x + dx, it.y) }, exit = exit?.let { Point(it.x + dx, it.y) },
        )
    }
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
                nums(m["arrival"])?.let { Point(it[0], it[1]) },
                (m["chests_count"] as? Number)?.toInt() ?: 0,
            )
        }
        return Spec(id, d["name"].toString(), d["era"].toString(), d["boss"]?.toString() ?: "", maps)
    }

    private class Run(val ctx: Context, val spec: Spec) {
        val mobs = Battle.Setup.from(ctx.params, "skill_")
        val bossSetup = if (ctx.bool("boss_setup")) Battle.Setup.from(ctx.params, "boss_skill_") else mobs
        val fightAll = ctx.bool("fight_all")
        var map = -1
        var shift = 0.0
        var chestsDone = 0
        var cleared = false
        var onBossMap = false
        var bossNext = false
        var bossBeaten = false
        var phantom = false
        var entered = false
        /** The map may have changed (after taking stairs): read its name again. */
        var moved = true
        var order: List<Int> = emptyList()
        // the run's report
        val start = System.currentTimeMillis()
        val items = LinkedHashMap<String, Int>()
        val rewards = LinkedHashMap<String, Int>()
        var chestsTotal = 0
        var skipped = false
        /** What the run is doing (the live log shows it again after a battle). */
        var task = ""
    }

    private fun task(r: Run, text: String) { r.task = text; MacroLog.status(text) }

    fun run(ctx: Context, id: String): String {
        val r = Run(ctx, spec(ctx, id))
        val outcome = try { runInner(r) } catch (s: StopScenario) { s.message ?: "stopped" }
        return report(r, outcome)
    }

    private fun runInner(r: Run): String {
        val ctx = r.ctx
        val spec = r.spec
        MacroLog.i("${spec.name}: key ${ctx.str("key")}, cat ticket ${ctx.bool("cat_ticket")}, " +
            "fight all ${r.fightAll}, mobs ${r.mobs}, boss ${r.bossSetup}")
        // started on the boss gate's question, or outside: else already inside
        if (!bossGate(r) && whereAmI(r) == null && !Battle.inBattle(ctx)) {
            enter(r)?.let { return it }
        }
        repeat(300) {
            if (r.phantom) return phantomDone(r)
            val idx = if (r.map >= 0 && !r.moved) r.map else whereAmI(r)
            r.moved = false
            if (idx == null) {
                if (fight(r)) return@repeat
                if (bossGate(r)) return@repeat
                // not on one of the dungeon's maps any more: the run ended
                return finish(r)
            }
            if (idx != r.map) {
                // line the layout up with where the party arrived (only when it just came in)
                val arrival = spec.maps[idx].arrival
                val party = arrival?.let { a -> AreaNav.look(ctx) { emptyList() }.candidates.minByOrNull { abs(it.x - a.x) + 3 * abs(it.y - a.y) } }
                r.shift = if (arrival != null && party != null && abs(party.y - arrival.y) < AreaNav.ROW_TOL && abs(party.x - arrival.x) < 0.025)
                    party.x - arrival.x else 0.0
                MacroLog.i("layout offset ${"%.3f".format(r.shift)}")
                r.map = idx; r.chestsDone = 0; r.cleared = false
                r.onBossMap = spec.maps[idx].boss
                r.order = chestOrder(spec.maps[idx].shifted(r.shift), party)
                r.chestsTotal += spec.maps[idx].chests.size.takeIf { it > 0 } ?: spec.maps[idx].chestsCount
                task(r, "Map ${idx + 1}/${spec.maps.size}: ${spec.maps[idx].names.first()}")
                ctx.count("maps")
            }
            val ms = spec.maps[idx].shifted(r.shift)
            when {
                ms.layout == null -> sweep(r)
                r.chestsDone < ms.chests.size -> takeChest(r, ms, ms.chests[r.order.getOrElse(r.chestsDone) { r.chestsDone }])
                r.fightAll && !r.cleared -> grind(r, ms)
                ms.exit != null -> leave(r, ms, idx)
                else -> sweep(r)
            }
            if (r.bossBeaten) return finish(r)
        }
        return "${spec.name}: gave up after 300 steps"
    }

    /** The chests in the order that makes the shortest walk from [from] through all of them to the exit. */
    private fun chestOrder(ms: MapSpec, from: Point?): List<Int> {
        val layout = ms.layout ?: return ms.chests.indices.toList()
        val start = from ?: ms.arrival ?: return ms.chests.indices.toList()
        fun perms(l: List<Int>): List<List<Int>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }
        val best = perms(ms.chests.indices.toList()).minByOrNull { order ->
            var at = start
            var cost = 0.0
            for (i in order) { cost += AreaNav.pathCost(layout, at, ms.chests[i]); at = ms.chests[i] }
            ms.exit?.let { cost += AreaNav.pathCost(layout, at, it) }
            cost
        } ?: ms.chests.indices.toList()
        MacroLog.i("chest order: ${best.map { it + 1 }}")
        return best
    }

    /** "Obtained Dark Residue x200." → items (OCR reads 0 as O at times). One banner line at a time. */
    private fun noteItems(r: Run, lines: List<Ocr.Line>) {
        for (l in lines) {
            val m = Regex("""^\s*Obtained\s+([A-Za-z'\- ]+?)\s*[x×X]\s*([0-9OoIl,]+)""").find(l.text) ?: continue
            val n = m.groupValues[2].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull() ?: continue
            val name = m.groupValues[1].trim()
            r.items[name] = (r.items[name] ?: 0) + n
        }
    }

    /**
     * The end screens: "Congratulations" rows ("Medium Class Scroll  × 2") and "Items Obtained"
     * ("Dark Residue / Amount 4,250 / Held …").
     */
    private fun noteRewards(r: Run, lines: List<Ocr.Line>) {
        fun num(t: String) = t.replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(Regex("[^0-9]"), "").toIntOrNull()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if ("congratulations" in all) {
            for (c in lines.filter { Regex("""^\s*[x×X]\s*[0-9OoIl,]+\s*$""").matches(it.text) }) {
                val name = lines.filter { it !== c && abs(it.center.y - c.center.y) < 0.03 && it.center.x < c.center.x && Regex("[A-Za-z]{3}").containsMatchIn(it.text) }
                    .maxByOrNull { it.center.x }?.text?.trim() ?: continue
                val n = num(c.text) ?: continue
                r.rewards[name] = (r.rewards[name] ?: 0) + n
            }
        }
        if ("items obtained" in all) {
            for (a in lines.filter { Ocr.norm(it.text).startsWith("amount") }) {
                val n = num(a.text.substringAfter("mount")) ?: lines.firstOrNull { it !== a && abs(it.center.y - a.center.y) < 0.03 && it.center.x > a.center.x }?.let { num(it.text) } ?: continue
                val name = lines.filter { it.center.y < a.center.y && a.center.y - it.center.y < 0.1 && abs(it.center.x - a.center.x) < 0.15 && Regex("[A-Za-z]{3}").containsMatchIn(it.text) && !Ocr.norm(it.text).startsWith("items") }
                    .maxByOrNull { it.center.y }?.text?.trim() ?: continue
                r.rewards[name] = n // the run's total of that item (shown once)
            }
        }
    }

    /** One line about the run: how long, what it got. Also appended to the app's runs.log. */
    private fun report(r: Run, outcome: String): String {
        val secs = (System.currentTimeMillis() - r.start) / 1000
        val st = r.ctx.stats
        val items = r.items.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val rewards = r.rewards.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val text = "$outcome — ${secs / 60}m ${secs % 60}s; " +
            (if (r.skipped) "skipped with a Cat Express Ticket; " else "chests ${st["chests"] ?: 0}/${r.chestsTotal}, battles ${st["battles"] ?: 0}; ") +
            (if (r.skipped) "" else "chest items: $items; ") + "rewards: $rewards; White Key: ${if (r.phantom) "yes" else "no"}"
        runCatching {
            val o = org.json.JSONObject().put("when", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.start)))
                .put("dungeon", r.spec.name).put("key", r.ctx.str("key")).put("seconds", secs).put("outcome", outcome)
                .put("skipped", r.skipped).put("chests", st["chests"] ?: 0).put("chests_total", r.chestsTotal)
                .put("battles", st["battles"] ?: 0).put("white_key", r.phantom).put("items", org.json.JSONObject(r.items as Map<*, *>)).put("rewards", org.json.JSONObject(r.rewards as Map<*, *>))
            java.io.File(r.ctx.debugDir.parentFile, "runs.log").appendText(o.toString() + "\n")
        }.onFailure { MacroLog.e("could not save the run report", it) }
        MacroLog.status(text)
        return text
    }

    private fun phantomDone(r: Run) = "${r.spec.name} done: the Phantom Crystal Dimension is open (White Key)"

    // --- 1. entering ------------------------------------------------------------------
    /** Through the Another Dungeon door. A message when the run ends here (skipped), else null. */
    private fun enter(r: Run): String? {
        val ctx = r.ctx
        val red = ctx.str("key").lowercase() == "red"
        val difficulty = if (red) "Very Hard" else "Hard"
        if (!waitForField(ctx)) ctx.stop("not on the field: start next to the Another Dungeon door in the Spacetime Rift")
        task(r, "Entering ${r.spec.name} ($difficulty)")
        val door = ctx.find(BUBBLE) ?: ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift)")
        ctx.tap(door); ctx.wait(2.5)
        // the map fades in: give it a few reads
        var opened = false
        for (i in 0 until 6) {
            if (ctx.readText().any { Ocr.norm(it.text) == "another dungeon" }) { opened = true; break }
            ctx.wait(1.0)
        }
        if (!opened) ctx.stop("the door didn't open the Another Dungeon map: ${seen(ctx)}")
        tapText(r, eraLabel(r.spec.era)); ctx.wait(1.5)
        tapText(r, r.spec.name, minY = 0.2, maxX = 0.62) ?: ctx.stop("${r.spec.name} not on the ${r.spec.era} map: ${seen(ctx)}")
        ctx.wait(2.0)
        // the panel's counters at the bottom: green keys, red keys, Cat Express Tickets ("17/50"); their "+" buy more
        val tickets = LiveLog.without { ctx.readText() }.filter { it.center.y > 0.85 && it.center.x > 0.8 }
            .firstNotNullOfOrNull { Regex("""(\d+)\s*/\s*50""").find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        MacroLog.i("Cat Express Tickets: ${tickets ?: "not read"}")
        val skip = ctx.bool("cat_ticket") && (tickets ?: 0) > 0
        if (ctx.bool("cat_ticket") && !skip) MacroLog.i("no Cat Express Ticket to use: walking the dungeon instead")
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
        if (skip) {
            tapText(r, "Skip", exact = true) ?: ctx.stop("no Skip button (Cat Express Ticket): ${seen(ctx)}")
            // no confirmation: the game goes straight to "Congratulations" and the items
            ctx.count("cat_tickets")
            r.skipped = true
            ctx.wait(3.0)
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
        ctx.tap(AreaNav.MINIMAP); ctx.wait(1.5)
        ctx.refresh()
        if (Battle.inBattle(ctx)) return null // a battle started: no close tap (it would hit a portrait)
        val text = ctx.readText()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.0)
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
        task(r, "Map ${r.map + 1}: chest ${r.chestsDone + 1}/${ms.chests.size}")
        MacroLog.i("chest ${r.chestsDone + 1}/${ms.chests.size} at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)})")
        AreaNav.walkLayout(ctx, ms.layout!!, chest, onStep(r))
        // close by: the chest's "!" shows; look around the spot, a little further each side, until it does
        val party = AreaNav.look(ctx) { emptyList() }.party
        val dir = if (party != null && chest.x < party.x) -1.0 else 1.0
        if (openBubble(r)) { r.chestsDone++; ctx.count("chests"); return }
        for (k in 1..6) {
            val side = if (k % 2 == 1) dir else -dir
            AreaNav.hold(ctx, side, 0.006 * k)
            fight(r)
            ctx.wait(0.6)
            if (openBubble(r)) { r.chestsDone++; ctx.count("chests"); return }
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
        val read = ctx.readText()
        val text = read.joinToString(" ") { it.text }
        MacroLog.i("\"!\": ${text.take(120)}")
        noteItems(r, read)
        // a chest found while looking around a map without a layout
        if (Ocr.norm(text).contains("obtained") && r.spec.maps.getOrNull(r.map)?.layout == null) ctx.count("chests")
        if (bossGate(r)) { fight(r); return true }
        if (fight(r)) return true // the boss (or a guard) behind it
        ctx.tap(Point(0.5, 0.75)); ctx.wait(1.2)
        waitForField(ctx, 6)
        return true
    }

    /** Walk to the map's exit and take its ground arrow (tap it); flick as a fallback. */
    private fun leave(r: Run, ms: MapSpec, idx: Int) {
        val ctx = r.ctx
        r.moved = true
        task(r, "Map ${idx + 1}: to the stairs down")
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
        task(r, "${ms.names.first()}: fighting the enemies left (back and forth)")
        var dir = 1.0
        var quiet = 0
        while (quiet < 12) {
            AreaNav.hold(ctx, dir, 0.05)
            dir = -dir
            if (fight(r)) quiet = 0 else quiet++
            if (r.cleared) return
        }
        MacroLog.i("no encounter in $quiet walks: taking ${ms.names.first()} as cleared")
        r.cleared = true
    }

    private fun noEnemiesLeft(text: String) = "enemies have disappeared" in text || ("enemies" in text && "disappeared" in text)

    /** One read of the screen after a win: the map's enemies gone, the White Key. */
    private fun readAfterWin(r: Run, quick: Boolean = false) {
        // the banner sits at the top middle: reading only there is quicker (it shows for a moment)
        val lines = if (quick) r.ctx.readText(Region(0.15, 0.0, 0.7, 0.25)) else r.ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if (!quick) { noteRewards(r, lines); endNotSkip(r, lines) }
        if (!r.cleared && noEnemiesLeft(all)) { MacroLog.i("the enemies have disappeared"); r.cleared = true; r.ctx.count("maps_cleared") }
        if ("white key" in all || "phantom crystal" in all) {
            if (!r.phantom) MacroLog.i("White Key: the Phantom Crystal Dimension is open")
            r.phantom = true
        }
    }

    /**
     * A map without a known layout (the boss's): walk to one end, then to the other in
     * short steps, tapping every "!" (chests, the boss); then try the streets above/below.
     */
    private fun sweep(r: Run) {
        val ctx = r.ctx
        if (r.fightAll && !r.cleared && !r.onBossMap) { grind(r, r.spec.maps[r.map]); return }
        task(r, "Map ${r.map + 1}: looking around (chest, boss)")
        for (vertical in listOf(null, true, false)) {
            if (vertical != null) { AreaNav.flick(ctx, vertical); fight(r); if (r.bossBeaten) return }
            for (dir in listOf(-1.0, 1.0)) {
                var still = 0
                var last: Point? = null
                for (step in 0 until 30) {
                    if (bossGate(r)) { fight(r); if (r.bossBeaten) return }
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
        // the run's end follows the boss battle's results: read every screen for the White Key
        val out = Battle.fight(ctx, afterWin = { readAfterWin(r) }) { c ->
            // the enemies' names show while a skill list is open: look on the boss's map
            if (!r.bossNext && r.onBossMap && r.spec.boss.isNotEmpty()) {
                val names = Battle.enemyNames(c)
                wasBoss = Ocr.norm(names).contains(Ocr.norm(r.spec.boss))
                MacroLog.i("enemies: ${names.take(80)}${if (wasBoss) " (boss)" else ""}")
            }
            if (r.bossNext) { wasBoss = true; r.bossNext = false; MacroLog.i("boss battle") }
            if (wasBoss) r.bossSetup else r.mobs
        }
        when (out) {
            Battle.Outcome.LOST -> ctx.stop("party was defeated in ${r.spec.name}")
            Battle.Outcome.NONE -> {
                // the boss's question pops up where the walk triggers it
                if (r.onBossMap && ctx.find("field_menu_button") == null && bossGate(r)) return fight(r)
                return false
            }
            Battle.Outcome.WON -> {
                ctx.count("battles")
                if (r.task.isNotEmpty()) MacroLog.status(r.task)
                if (wasBoss) { r.bossBeaten = true; ctx.count("bosses") }
                // "The enemies have disappeared" flashes for a moment after a map's last enemy
                val end = System.currentTimeMillis() + 3000
                while (!r.cleared && !r.bossBeaten && System.currentTimeMillis() < end) readAfterWin(r, quick = true)
                if (r.bossBeaten) checkPhantom(r)
            }
        }
        return true
    }

    /** "A major trial awaits you beyond this point. Proceed?": the boss. Yes, and the next battle is the boss. */
    private fun bossGate(r: Run): Boolean {
        val lines = r.ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if ("major trial" !in all && "proceed" !in all) return false
        val yes = lines.firstOrNull { Ocr.norm(it.text) == "yes" } ?: return false
        task(r, "The boss: proceeding")
        r.ctx.tapExact(yes.center)
        r.bossNext = true
        r.ctx.wait(3.0)
        return true
    }

    // --- 6. the end ------------------------------------------------------------------
    /** Tap through the completion screens, noting a White Key / Phantom Crystal Dimension. */
    private fun finish(r: Run): String {
        val ctx = r.ctx
        repeat(25) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) {
                if (whereAmI(r) != null) return "${r.spec.name}: the boss is beaten but the party is still inside"
                return if (r.phantom) phantomDone(r) else "${r.spec.name} done"
            }
            if (Battle.inBattle(ctx)) { fight(r); return@repeat }
            val lines = ctx.readText()
            readPhantom(r, lines)
            noteRewards(r, lines)
            // "Continue to skip?  End / Skip": End (Skip would spend another key and ticket)
            if (endNotSkip(r, lines)) return@repeat
            tapText(r, "OK", "Close", "Next", exact = true, tries = 1)
                ?: ctx.tap(Point(0.5, 0.2)) // the rest closes with a tap anywhere: up here, away from buttons
            ctx.wait(2.0)
        }
        return "${r.spec.name}: finished, but stuck on the screens after it (${seen(ctx)})"
    }

    /** On the "Continue to skip?" box, tap End. True if it was there. */
    private fun endNotSkip(r: Run, lines: List<Ocr.Line>): Boolean {
        if (lines.none { Ocr.norm(it.text).contains("continue to skip") }) return false
        val end = lines.firstOrNull { Ocr.norm(it.text) == "end" } ?: return false
        MacroLog.i("\"Continue to skip?\": End")
        r.ctx.tapExact(end.center); r.ctx.wait(2.5)
        return true
    }

    private fun readPhantom(r: Run, lines: List<Ocr.Line>) {
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if ("white key" in all || "phantom crystal" in all) {
            if (!r.phantom) MacroLog.i("White Key: the Phantom Crystal Dimension is open")
            r.phantom = true
        }
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
