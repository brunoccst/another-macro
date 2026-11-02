package io.github.brunoccst.anothermacro

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One run of an Another Dungeon (data in templates/another_dungeon/dungeons.yaml):
 *
 *  1. enter: World Map → Another Dungeon → era tab → the dungeon → the difficulty of the
 *     chosen key (green = Hard, red = Very Hard), how many keys, the Cat Express Ticket;
 *  2. on every map: open the area map, walk to each unopened chest and open it, fighting
 *     the encounters on the way (with the mob skill setup);
 *  3. "fight all enemies": once a map's chests are taken, walk back and forth fighting until
 *     the game says no enemies are left;
 *  4. go down to the next map (the stairs icon farthest from where the party arrived);
 *  5. the last map: its chest, then the boss (with the boss skill setup when chosen);
 *  6. stop when the run ends, and say so when a White Key opened the Phantom Crystal Dimension.
 *
 * Every screen outside the field is driven by its text (on-device OCR), and nothing that
 * costs premium currency is ever confirmed: a key/stone purchase offer stops the run.
 */
object AnotherDungeon {
    data class MapSpec(val names: List<String>, val chests: Int, val boss: Boolean)
    data class Spec(val id: String, val name: String, val era: String, val boss: String, val maps: List<MapSpec>)

    private val MAP_BUTTON = Point(0.385, 0.917)
    private const val ICONS = "templates/another_dungeon/"

    /** Words that mean the game wants something bought: never tap on, cancel and stop. */
    private val PURCHASE_WORDS = listOf("purchase", "chronos stone", "tsubura", "buy ", "insufficient", "not enough keys", "restore keys")

    @Suppress("UNCHECKED_CAST")
    fun spec(ctx: Context, id: String): Spec {
        val text = ctx.assets.open("${ICONS}dungeons.yaml").bufferedReader().use { it.readText() }
        val all = Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Map<String, Any?>>
        val d = all[id] ?: throw IllegalArgumentException("unknown Another Dungeon '$id' (known: ${all.keys.joinToString()})")
        val maps = (d["maps"] as List<Map<String, Any?>>).map { m ->
            MapSpec((m["names"] as List<*>).map { it.toString() }, (m["chests"] as Number).toInt(), m["boss"] == true)
        }
        return Spec(id, d["name"].toString(), d["era"].toString(), d["boss"]?.toString() ?: "", maps)
    }

    /** What the run has done on the current map. */
    private class MapState(val index: Int) {
        val opened = ArrayList<Point>()            // map spots of chests taken (or given up on)
        val tries = HashMap<Point, Int>()
        var arrival: Point? = null
        var cleared = false                        // "no enemies left" seen (or nothing comes any more)
        var doneChests = 0
    }

    private class Run(val ctx: Context, val spec: Spec) {
        val mobs = Battle.Setup.from(ctx.params, "skill_")
        val bossSetup = if (ctx.bool("boss_setup")) Battle.Setup.from(ctx.params, "boss_skill_") else mobs
        val fightAll = ctx.bool("fight_all")
        var state: MapState? = null
        var bossNext = false
        var bossBeaten = false
        var phantom = false
    }

    fun run(ctx: Context, id: String): String {
        val spec = spec(ctx, id)
        val r = Run(ctx, spec)
        MacroLog.i("${spec.name}: key ${ctx.str("key")} x${ctx.str("keys")}, cat ticket ${ctx.bool("cat_ticket")}, " +
            "fight all ${r.fightAll}, mobs ${r.mobs}, boss ${r.bossSetup}")
        if (whereAmI(r) == null) enter(r)
        repeat(200) {
            if (r.phantom) return "${spec.name} done: the Phantom Crystal Dimension is open (White Key)"
            val idx = whereAmI(r)
            if (idx == null) {
                if (fight(r)) return@repeat
                // out of the dungeon's maps: the run ended (completion screens, back outside)
                finish(r)?.let { return it }
                return@repeat
            }
            step(r, idx)
            if (r.bossBeaten) finish(r)?.let { return it }
        }
        return "${spec.name}: gave up after 200 steps"
    }

    // --- 1. entering ------------------------------------------------------------------
    private fun enter(r: Run) {
        val ctx = r.ctx
        val key = ctx.str("key").lowercase()
        val difficulty = if (key == "red") "Very Hard" else "Hard"
        waitForField(ctx)
        ctx.tap(MAP_BUTTON); ctx.wait(2.5)
        tapText(r, "Another Dungeon") ?: ctx.stop("no Another Dungeon button on the world map: ${seen(ctx)}")
        ctx.wait(2.0)
        tapText(r, r.spec.era)
        ctx.wait(1.5)
        tapText(r, r.spec.name) ?: ctx.stop("${r.spec.name} not in the Another Dungeon list: ${seen(ctx)}")
        ctx.wait(2.0)
        tapText(r, difficulty, exact = true) ?: ctx.stop("no '$difficulty' choice for ${r.spec.name}: ${seen(ctx)}")
        ctx.wait(2.0)
        setKeys(r)
        if (ctx.bool("cat_ticket")) {
            if (tapText(r, "Cat Express") != null) { ctx.count("cat_tickets"); ctx.wait(1.5) }
            else MacroLog.i("no Cat Express Ticket offered (none left?)")
        }
        tapText(r, "Enter", "Start", "Begin", "Go", "OK", "Yes", exact = true) ?: ctx.stop("no button to enter ${r.spec.name}: ${seen(ctx)}")
        ctx.count("entered")
        ctx.wait(4.0)
        // a confirmation after Enter (e.g. "Use 1 key?")
        if (!waitForField(ctx, 8)) {
            tapText(r, "Yes", "OK", "Enter", exact = true)
            if (!waitForField(ctx, 30)) ctx.stop("didn't arrive in ${r.spec.name}: ${seen(ctx)}")
        }
    }

    /** How many keys: a number (+ taps from 1) or "all" (Max, or + until it stops changing). */
    private fun setKeys(r: Run) {
        val ctx = r.ctx
        val want = ctx.str("keys").lowercase()
        val n = want.toDoubleOrNull()?.toInt()
        if (n == 1) return
        if (want == "all" || n == null) {
            if (tapText(r, "Max", exact = true) != null) { ctx.wait(0.8); return }
            repeat(10) { tapText(r, "+", exact = true) ?: return; ctx.wait(0.5) }
            return
        }
        repeat(n - 1) { tapText(r, "+", exact = true) ?: return MacroLog.i("no + to raise the key count; using what is set"); ctx.wait(0.5) }
    }

    // --- 2-5. inside --------------------------------------------------------------------
    private class Look(val view: AreaNav.View, val chests: List<Point>, val stairs: List<Point>)

    private fun look(r: Run): Look {
        val ctx = r.ctx
        var chests = emptyList<Point>()
        var stairs = emptyList<Point>()
        val view = AreaNav.look(ctx, readText = true) { f ->
            chests = AreaNav.findIcons(ctx, f, "${ICONS}map_chest_icon.png")
            stairs = AreaNav.findIcons(ctx, f, "${ICONS}map_stairs_down_icon.png") + AreaNav.findIcons(ctx, f, "${ICONS}map_stairs_up_icon.png")
            chests + stairs
        }
        return Look(view, chests, stairs)
    }

    /** Which of the dungeon's maps the area map names (its title "Dogma Tower - <map>"), or null. */
    private fun whereAmI(r: Run): Int? {
        val ctx = r.ctx
        if (!waitForField(ctx, 10)) return null
        ctx.tap(AreaNav.MINIMAP); ctx.wait(2.0)
        val text = ctx.readText()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.5)
        return mapIndex(r, text)
    }

    private fun mapIndex(r: Run, text: List<Ocr.Line>): Int? {
        val title = text.filter { it.center.y < 0.15 }.joinToString(" ") { it.text }
        if (!Ocr.norm(title).contains(Ocr.norm(r.spec.name))) return null
        val part = title.substringAfter("-").trim()
        return r.spec.maps.indices.maxByOrNull { i -> r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) } }
            ?.takeIf { i -> r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) } > 0.6 }
    }

    private fun step(r: Run, idx: Int) {
        val ctx = r.ctx
        val st = r.state?.takeIf { it.index == idx } ?: MapState(idx).also {
            r.state = it
            MacroLog.i("map ${idx + 1}/${r.spec.maps.size}: ${r.spec.maps[idx].names.first()}")
            ctx.count("maps")
        }
        val ms = r.spec.maps[idx]
        val l = look(r)
        if (st.arrival == null) st.arrival = l.view.party
        // 2. chests not taken yet (an opened chest's icon goes away or stays where we were)
        val pending = l.chests.filter { c -> st.opened.none { near(it, c) } }
        if (pending.isNotEmpty() && st.doneChests < ms.chests + 2) {
            val party = l.view.party ?: Point(0.5, 0.5)
            val chest = pending.minByOrNull { abs(it.x - party.x) + 3 * abs(it.y - party.y) }!!
            MacroLog.i("chest at (${"%.2f".format(chest.x)}, ${"%.2f".format(chest.y)}), ${pending.size} left on this map")
            val there = AreaNav.steer(ctx, look = { look(r).view }, target = { v -> nearestTo(v.targets, chest) }, onStep = { fight(r) })
            if (there && openChest(r)) {
                st.opened += chest; st.doneChests++; ctx.count("chests")
            } else {
                val t = (st.tries[chest] ?: 0) + 1
                st.tries[chest] = t
                if (t >= 2) { MacroLog.i("giving up on the chest at $chest"); st.opened += chest }
            }
            return
        }
        // 3. every enemy
        if (r.fightAll && !st.cleared) { grind(r, st); return }
        // 5. the boss
        if (ms.boss) { goToBoss(r, st, l); return }
        // 4. down to the next map
        val exit = l.stairs.filter { s -> st.arrival?.let { a -> dist(a, s) > 0.06 } ?: true }
            .maxByOrNull { s -> st.arrival?.let { dist(it, s) } ?: 0.0 }
        if (exit == null) ctx.stop("no way down found on ${ms.names.first()} (stairs icon not recognised)")
        MacroLog.i("to the stairs at (${"%.2f".format(exit.x)}, ${"%.2f".format(exit.y)})")
        AreaNav.steer(ctx, look = { look(r).view }, target = { v -> nearestTo(v.targets, exit) }, onStep = { fight(r) })
        takeStairs(r, idx)
    }

    /** Stand on the stairs and take them: flick down, then up, then walk on, until the map changes. */
    private fun takeStairs(r: Run, idx: Int) {
        val ctx = r.ctx
        for (move in listOf("down", "up", "left", "right")) {
            when (move) {
                "down" -> AreaNav.flick(ctx, up = false)
                "up" -> AreaNav.flick(ctx, up = true)
                "left" -> AreaNav.hold(ctx, -1.0, 0.05)
                else -> AreaNav.hold(ctx, 1.0, 0.1)
            }
            fight(r)
            ctx.wait(2.0)
            val now = whereAmI(r)
            if (now != idx) return
        }
        MacroLog.i("the stairs didn't take us anywhere yet")
    }

    /** Walk back and forth fighting until the game says no enemies are left (or none come any more). */
    private fun grind(r: Run, st: MapState) {
        val ctx = r.ctx
        var dir = 1.0
        var quietWalks = 0
        while (quietWalks < 14) {
            AreaNav.hold(ctx, dir, 0.06)
            dir = -dir
            val fought = fight(r)
            if (noEnemiesLeft(ctx)) { MacroLog.i("no enemies left"); st.cleared = true; return }
            if (fought) quietWalks = 0 else quietWalks++
        }
        MacroLog.i("no encounter in $quietWalks walks: taking the map as cleared")
        st.cleared = true
    }

    private fun noEnemiesLeft(ctx: Context): Boolean {
        val all = ctx.readText().joinToString(" ") { Ocr.norm(it.text) }
        return ("enem" in all || "monster" in all) && (" no " in " $all " || "defeated all" in all || "all enemies" in all || "none" in all)
    }

    /** The last map: walk away from where we came in until the boss battle starts. */
    private fun goToBoss(r: Run, st: MapState, l: Look) {
        val ctx = r.ctx
        r.bossNext = true
        val from = st.arrival ?: l.view.party ?: Point(0.5, 0.5)
        val far = l.stairs.filter { dist(it, from) > 0.06 }.maxByOrNull { dist(it, from) }
        if (far != null) {
            AreaNav.steer(ctx, look = { look(r).view }, target = { v -> nearestTo(v.targets, far) }, onStep = { fight(r) })
            if (r.bossBeaten) return
            AreaNav.flick(ctx, up = true); fight(r)
            if (r.bossBeaten) return
        }
        val dir = if (from.x < 0.5) 1.0 else -1.0
        repeat(6) {
            AreaNav.hold(ctx, dir, 0.08)
            fight(r)
            if (r.bossBeaten) return
        }
        AreaNav.flick(ctx, up = true); fight(r)
        if (!r.bossBeaten) MacroLog.i("no boss battle yet")
    }

    /** Fight a battle if one is on screen: boss setup for the boss. True if there was one. */
    private fun fight(r: Run): Boolean {
        val ctx = r.ctx
        var wasBoss = false
        val out = Battle.fight(ctx) { c ->
            val text = if (r.spec.boss.isNotEmpty()) c.readText().joinToString(" ") { it.text } else ""
            // the boss's name on screen; or, on the last map once its mobs are all gone, any battle
            wasBoss = (r.spec.boss.isNotEmpty() && Ocr.norm(text).contains(Ocr.norm(r.spec.boss))) ||
                (r.bossNext && r.state?.cleared == true)
            if (wasBoss) { MacroLog.i("boss battle"); r.bossSetup } else r.mobs
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

    /** A chest by the party: tap it (template, or the spots around the party), then clear its reward box. */
    private fun openChest(r: Run): Boolean {
        val ctx = r.ctx
        ctx.refresh()
        val spots = ctx.find("dungeon_field_chest")?.let { listOf(it.center) }
            ?: listOf(Point(0.5, 0.55), Point(0.42, 0.55), Point(0.58, 0.55), Point(0.5, 0.45))
        for (p in spots) {
            ctx.tapExact(p); ctx.wait(1.5)
            val text = ctx.readText()
            val all = text.joinToString(" ") { Ocr.norm(it.text) }
            if ("obtained" in all || "received" in all || "residue" in all || "treasure" in all) {
                val ok = text.firstOrNull { Ocr.norm(it.text) in setOf("ok", "close") }
                if (ok != null) ctx.tapExact(ok.center) else ctx.tap(Point(0.5, 0.8))
                ctx.wait(1.2)
                waitForField(ctx, 10)
                return true
            }
            if (!waitForField(ctx, 3)) { ctx.tap(Point(0.5, 0.8)); ctx.wait(1.0) }
        }
        MacroLog.i("no chest reward seen after tapping around the party")
        return false
    }

    // --- 6. the end ------------------------------------------------------------------
    /**
     * Tap through the completion screens, noting a White Key / Phantom Crystal Dimension.
     * Null when the party turns out to be still inside (that wasn't the boss after all).
     */
    private fun finish(r: Run): String? {
        val ctx = r.ctx
        repeat(20) {
            checkPhantom(r)
            if (r.phantom) return "${r.spec.name} done: the Phantom Crystal Dimension is open (White Key)"
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) {
                if (whereAmI(r) != null) { r.bossBeaten = false; return null }
                return "${r.spec.name} done"
            }
            if (Battle.inBattle(ctx)) { fight(r); return@repeat }
            val text = ctx.readText()
            val ok = text.firstOrNull { Ocr.norm(it.text) in setOf("ok", "close", "next", "leave", "exit") }
            if (ok != null) ctx.tapExact(ok.center) else ctx.tap(Point(0.5, 0.8))
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
    /** Tap the first on-screen text matching one of [wants]; never on a purchase offer. */
    private fun tapText(r: Run, vararg wants: String, exact: Boolean = false): Ocr.Line? {
        val ctx = r.ctx
        repeat(3) {
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            PURCHASE_WORDS.firstOrNull { it.trim() in all }?.let { w ->
                lines.firstOrNull { Ocr.norm(it.text) in setOf("cancel", "no", "close") }?.let { ctx.tapExact(it.center) }
                ctx.stop("safety stop: the game offered a purchase ('$w'); nothing bought")
            }
            for (want in wants) {
                val n = Ocr.norm(want)
                val hit = lines.firstOrNull { l ->
                    val t = Ocr.norm(l.text)
                    if (want == "+") l.text.trim() == "+" else if (exact) t == n else t == n || t.contains(n) || Ocr.similarity(l.text, want) >= 0.8
                }
                if (hit != null) { ctx.tapExact(hit.center); return hit }
            }
            ctx.wait(1.0)
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

    private fun near(a: Point, b: Point) = abs(a.x - b.x) < 0.03 && abs(a.y - b.y) < 0.05
    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun nearestTo(points: List<Point>, p: Point) = points.filter { dist(it, p) < 0.08 }.minByOrNull { dist(it, p) } ?: p
}
