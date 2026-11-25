package anothermacro.logic

import macroforge.logic.Game

import macroforge.*

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * One run of an Another Dungeon (data in its macro file's `dungeon:` block, see docs/dungeon-data.md):
 *
 *  1. enter: the "!" over the Another Dungeon door → era tab → the dungeon's label → Hard
 *     (green key) or Very Hard (red key) → Move, or Skip when the user chose to spend a
 *     Cat Express Ticket;
 *  2. every map: follow its fixed path (the dungeon data's `path:`, planned from the map's
 *     layout by `another-macro dungeon-routes`) to each chest and sparkle and open it with the "!"
 *     bubble, fighting the battles on the way (after a battle the walk reads its spot again
 *     and goes on from there, see Route.kt); a map without a path walks its layout looking
 *     at the area map;
 *  3. a chest not found on the way gets a second try, walked to by the area map;
 *  4. a map with `enemies:` not false, whose "The enemies have disappeared" banner hasn't
 *     shown yet, with the `defeat_all` option on: walk back and forth fighting until the
 *     banner shows; then walk to the map's exit and take it;
 *  5. the last map (no layout): sweep it, tapping every "!"; the boss battle gets the boss
 *     skill setup when one is chosen;
 *  6. stop when the run ends, and say so when a White Key was found.
 *
 * Nothing that costs anything is ever confirmed: the "+" beside the panel's counters is
 * never tapped, and any purchase offer stops the run.
 */
object AnotherDungeon {
    data class MapSpec(val names: List<String>, val layout: AreaNav.Layout?, val chests: List<Point>, val exit: Point?, val boss: Boolean, val arrival: Point? = null, val chestsCount: Int = 0,
                       /** The fixed walk: one leg per chest (in order), then the exit. */
                       val path: List<Route.Leg>? = null,
                       /** False: the map is never cleared (the dungeon data's `enemies: false`). */
                       val enemies: Boolean = true,
                       /** A small map done by fixed steps instead of a layout: "bubble", "gate" (a "!" that travels on, Yes), "yes" (Yes to the travel question a flick opened), "U"/"D", "L 1000"/"R 1000", "chest L" (walk until the chest's "!"), "boss L" (walk to the boss). */
                       val steps: List<String>? = null,
                       /** Items picked up from the ground (sparkles): stops on the path, tapped when they show. */
                       val sparkles: List<Point> = emptyList(),
                       /** The sparkles are read off the area map's bag icons on arrival instead of the path's fixed stops. */
                       val sparkleIcons: Boolean = false,
                       /** Fish to harpoon (the area map's fish icons) and bait jars to empty (its jar icons): stops on the path. */
                       val fish: List<Point> = emptyList(),
                       val jars: List<Point> = emptyList(),
                       /** The way on is walking off the screen's edge at [exit] (no sign). */
                       val exitWalk: Boolean = false,
                       /** This map's area map scrolls up and down with the party (see [Spec.areaMapScrolls]). */
                       val scrolls: Boolean = false,
                       /** Only this sign template counts as the exit (another sign may sit nearer). */
                       val exitSign: String? = null,
                       /** This area map's zoom against walk.yaml's: the same hold walks this many times the table's distance on it. */
                       val walkScale: Double = 1.0,
                       /** A side trip that shows only some runs: a door icon on the area map near [Portal.sign] (read with the sparkles). */
                       val portal: Portal? = null) {
        /** The same map drawn [dx] further right. */
        fun shifted(dx: Double) = copy(
            layout = layout?.let { l -> AreaNav.Layout(l.segs.map { it.copy(x1 = it.x1 + dx, x2 = it.x2 + dx) }, l.links.map { it.copy(x = it.x + dx) }) },
            chests = chests.map { Point(it.x + dx, it.y) }, exit = exit?.let { Point(it.x + dx, it.y) },
            sparkles = sparkles.map { Point(it.x + dx, it.y) },
            portal = portal?.let { it.copy(sign = Point(it.sign.x + dx, it.sign.y), at = Point(it.at.x + dx, it.at.y)) },
            fish = fish.map { Point(it.x + dx, it.y) }, jars = jars.map { Point(it.x + dx, it.y) },
        )
    }
    data class Spec(
        val id: String, val name: String, val era: String, val boss: String, val maps: List<MapSpec>,
        /** A place on the era's map that opens a smaller map holding the dungeon's label. */
        val area: String? = null,
        /** The label's first line when the game wraps the name. */
        val label: String? = null,
        /** Every look at the area map lines the layout up with the streets it shows. */
        val areaMapScrolls: Boolean = false,
        /** A walk's first second may look still on screen: no late-start correction. */
        val cameraLags: Boolean = false,
        /** Keys one run takes (the dungeon data's `key_cost`). */
        val keyCost: Int = 1,
        /** Characters the party must hold to enter (the dungeon data's `requires`). */
        val requires: List<String> = emptyList(),
    )

    private const val BUBBLE = "field_interact_bubble"
    /** A sparkle's bag icon on the area map. */
    private const val BAG_ICON = "templates/another_dungeon/area_map_bag.png"
    /** The area map's door icon (two arrows), a way to another map. */
    private const val DOOR_ICON = "templates/another_dungeon/area_map_door.png"
    /** How far above its spot on the street the bag icon is drawn (area-map fraction). */
    private const val BAG_ABOVE = 0.027
    /** The bubble's inside only, for bright or busy backgrounds. */
    private const val BUBBLE_CORE = "field_interact_bubble_core"
    /** The bubble drawn small, for a map shown zoomed out. */
    private const val BUBBLE_SMALL = "field_interact_bubble_small"
    /** The round bubble with a jar icon shown over a bait jar when the party stands by it. */
    private const val JAR_BUBBLE = "field_jar_bubble"
    /** How far the party walks each way while clearing a map's enemies (area-map units). */
    private const val GRIND_SPAN = 0.09

    /**
     * Words that mean the game wants something bought (game.yaml's safety words): never tap on,
     * cancel and stop. "buy " is left out on purpose (talk in dungeons uses it without a sale).
     */
    private fun buyWords(ctx: Context) = Game.of(ctx).buyWords.filter { it.trim() != "buy" }

    /**
     * The dungeon the macro [macro] runs: its file's `dungeon:` block (the macro running the step
     * names itself), with the chest area its `area` option picks.
     */
    fun spec(ctx: Context, macro: String): Spec {
        val file = ctx.findMacro(macro) as? FlowScenario ?: throw IllegalArgumentException("no macro '$macro' to read the dungeon from")
        val data = dungeonOf(file.spec, macro)
        val area = area(ctx.params)
        if (area != null && (data["areas"] as? Map<*, *>)?.containsKey(area) != true) MacroLog.i("area '$area': the dungeon's own data")
        return spec(data, macro, area)
    }

    /** The `area` option as an `areas:` key ("Demon's Den" -> "demon_s_den"), or null. */
    internal fun area(params: Map<String, String>): String? =
        params["area"]?.trim()?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.takeIf { it.isNotEmpty() }

    /** The `dungeon:` block of a parsed macro file. */
    @Suppress("UNCHECKED_CAST")
    internal fun dungeonOf(macroFile: Map<String, Any?>, macro: String): Map<String, Any?> =
        macroFile["dungeon"] as? Map<String, Any?> ?: throw IllegalArgumentException("the macro '$macro' has no dungeon: block")

    /** The dungeon in the text of the macro file [macro] (also read by the app's "How it works": HowItWorks.kt). */
    @Suppress("UNCHECKED_CAST")
    fun spec(macroText: String, macro: String, area: String? = null): Spec =
        spec(dungeonOf(Load(LoadSettings.builder().build()).loadFromString(macroText) as Map<String, Any?>, macro), macro, area)

    /**
     * The dungeon from a macro's `dungeon:` block [data]. A hub dungeon's other chest area [area]
     * (`areas:` key) replaces the keys it sets (its boss and maps); an area it doesn't list is the
     * dungeon's own (its default area).
     */
    @Suppress("UNCHECKED_CAST")
    fun spec(data: Map<String, Any?>, id: String, area: String? = null): Spec {
        val areas = data["areas"] as? Map<String, Any?> ?: emptyMap()
        val own = area?.let { areas[it] } as? Map<String, Any?>
        val d = data - "areas" + own.orEmpty()
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
                (m["path"] as? List<*>)?.mapNotNull { (it as? List<*>)?.let { leg -> Route.parseLeg(leg) } }?.takeIf { it.isNotEmpty() },
                m["enemies"] != false,
                (m["steps"] as? List<*>)?.map { it.toString() },
                lists(m["sparkles"]).map { Point(it[0], it[1]) },
                m["sparkle_icons"] == true,
                lists(m["fish"]).map { Point(it[0], it[1]) },
                lists(m["jars"]).map { Point(it[0], it[1]) },
                m["exit_walk"] == true,
                m["area_map_scrolls"] == true,
                m["exit_sign"]?.toString(),
                (m["walk_scale"] as? Number)?.toDouble() ?: 1.0,
                (m["portal"] as? Map<String, Any?>)?.let { p ->
                    val sign = nums(p["sign"]) ?: return@let null
                    val at = nums(p["at"]) ?: return@let null
                    Portal(Point(sign[0], sign[1]), Point(at[0], at[1]), p["what"]?.toString() ?: "portal")
                },
            )
        }
        return Spec(id, d["name"].toString(), d["era"].toString(), d["boss"]?.toString() ?: "", maps, d["area"]?.toString(), d["label"]?.toString(),
            d["area_map_scrolls"] == true, d["camera_lags"] == true, (d["key_cost"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
            (d["requires"] as? List<*>)?.map { it.toString() } ?: emptyList())
    }

    /**
     * A map's side trip that is open only some runs: its door icon shows on the area map near [sign];
     * [at] is the street spot under it, where the field's door sign is tapped. The trip's own map:
     * tap the "!" there, tap through, then the door sign back. [what] names it in the report.
     */
    data class Portal(val sign: Point, val at: Point, val what: String)

    private class Run(val ctx: Context, val spec: Spec, /** The key this run enters with: "green" (Hard) or "red" (Very Hard). */ val key: String) {
        // every fight is the basic macro battle's step with its skills as this dungeon macro has them
        // (battle.skill_1.. among its options, taken over from the battle macro: InnerParams); the boss
        // with the dungeon's own boss skills when set. (Done here, not by running that macro: the run
        // must tell the boss by its name on the first turn and read the White Key off the results.)
        val mobs = Battle.Setup.from(ctx.innerOptions("battle"), "skill_")
        val bossSetup = if (ctx.bool("boss_setup")) Battle.Setup.from(ctx.params, "boss_skill_") else mobs
        var map = -1
        var shift = 0.0
        var chestsDone = 0
        /** Following a path: how many of its legs (chests, sparkles) are done. */
        var legsDone = 0
        /** A stop just before was left out (its collect option is off): the next leg is walked by the area map, not its fixed steps. */
        var offPlan = false
        var cleared = false
        /** Chests on this map not found on the way: tried once more (by the area map) before the exit. */
        val retryLater = ArrayList<Point>()
        /** This map's chests already tried a second time (each gets one retry only). */
        val retried = HashSet<Point>()
        var onBossMap = false
        var bossNext = false
        /** Looks in a row that found no map of the dungeon while its boss wasn't beaten yet. */
        var notOnAMap = 0
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
        /** Chests the search gave up on, with where the map showed the party (the report names them: the log rolls over). */
        val missed = mutableListOf<String>()
        /** Where the run left its fixed path and looked its way by the area map or a sweep (told to the user). */
        val searched = mutableListOf<String>()
        var skipped = false
        /** What the run is doing (the live log shows it again after a battle). */
        var task = ""
        /** The chosen key ran out (read on the dungeon panel): the loop ends. */
        var outOfKeys = false
        /** The macro's counters when this run started. */
        val base: Map<String, Int> = HashMap(ctx.stats)
        /** The run's timed parts when it started ([Timings]): the report says where this run's time went. */
        val timesBase: Map<String, Timings.Entry> = ctx.timings.snapshot()
        /** Sparkle stops in a row that had none, with none picked up yet this run: the rest get only a glance. */
        var emptySparkles = 0
        /** A White Key was already held before entering. */
        var heldKey = false
        /** Enter even though a White Key is held (the loop was told to keep going). */
        var ignoreHeldKey = false
        /** Following a map's path: where the party is (by the walk table) and where the plan has it. */
        var pos: Point? = null
        var planPos: Point? = null
        /** The map the exit led to, already read on the area map (null: read it again). */
        var nextMap: Int? = null
        /** The followed position is unsure (a chest was looked for around its spot): read it on the map before the next leg. */
        var unsure = false
        /** Sparkles read off the area map on a map whose sparkles move ([MapSpec.sparkleIcons]), in walking order; null: not read yet. */
        var live: MutableList<Point>? = null
        /** The map an exit was just taken toward: wins a tie when two maps share a title. */
        var expect: Int? = null
        /** A BannerWatch is reading the whole screen for "the enemies have disappeared" (no extra look after a win needed). */
        var watching = false
        /** The direction of the last walk on the path (+1 right, -1 left): a chest is looked for that way first. */
        var lastDir = 0.0
        /** This map's open portal's spot, planned with the sparkles (null: none open). */
        var portalAt: Point? = null
        /** Side trips taken (or missed) this run, for the report. */
        val trips = mutableListOf<String>()
    }

    private fun task(r: Run, text: String) { r.task = text; MacroLog.status(text) }

    /** Which keys the loop uses, in order (red first), and how many runs with each (Int.MAX_VALUE = until it runs out). */
    internal data class KeyPlan(val keys: List<String>, val runs: Int)

    /**
     * The loop's plan from the options: one switch per key colour (`red_key`, `green_key`; a
     * dungeon that takes one colour only has only that switch), `runs` (a number) and `all_keys`
     * (on: until every switched-on key runs out, `runs` not used). With both colours on, `runs`
     * runs are done with each colour: red keys first, then green ones. No colour on: no runs.
     * Old saved options still work: `key` green/red when neither switch is there, `runs` "all".
     */
    internal fun keyPlan(p: Map<String, String>): KeyPlan {
        fun on(v: String?) = v?.trim()?.lowercase() in setOf("1", "true", "yes", "on", "y")
        val keys = if ("red_key" in p || "green_key" in p) listOf("red", "green").filter { on(p["${it}_key"]) }
            else listOf(p["key"]?.trim()?.lowercase()?.takeIf { it == "red" || it == "green" } ?: "green")
        val t = p["runs"]?.trim()?.lowercase()?.removeSuffix(".0")
        val runs = if (on(p["all_keys"]) || t == "all") Int.MAX_VALUE else t?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return KeyPlan(keys, runs)
    }

    /**
     * Run the dungeon by [keyPlan]: `runs` times in a row with each switched-on key colour, red
     * first ("all keys": each until it runs out); a colour that runs out ends its runs and the
     * next colour goes on. Keys are never bought. Before the first run the `party` option picks the game's party
     * (Party.kt). After every run a White Key is checked for: `white_key` = phantom runs the
     * Phantom Crystal Dimension and comes back to the loop, stop ends the loop, keep_going ignores it.
     */
    fun run(ctx: Context, id: String): String {
        val spec = spec(ctx, id)
        val plan = keyPlan(ctx.params)
        // the party first, from the field (the menu's Party page)
        // (by its number in the game's list, through the basic macro pick-party)
        Party.number(ctx.params["party"])?.let { n ->
            // inside a dungeon the menu has no party list: a run started there keeps the party in front
            if (waitForField(ctx, 5) && PhantomCrystal.inside(ctx)) {
                MacroLog.i("party $n: started inside a dungeon, keeping the party in front")
            } else if (waitForField(ctx, 5)) {
                val got = ctx.runBasic("pick-party", mapOf("party" to "$n"))
                if (got != "party $n picked") ctx.stop("party $n: $got")
            } else MacroLog.i("party $n: not on the field (a battle?), keeping the party in front")
        } ?: ctx.params["party"]?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "current" }?.let {
            MacroLog.i("the party option takes a number (1 = the first party of the list): keeping the party in front")
        }
        // no key switched on: only the party is picked
        if (plan.keys.isEmpty()) return "${spec.name}: no runs to do (no key switched on)"
        val onWhiteKey = ctx.params["white_key"]?.trim()?.lowercase() ?: "phantom"
        val start = System.currentTimeMillis()
        val lines = ArrayList<String>()
        var done = 0
        var phantoms = 0
        var ignore = false
        var end = ""
        val notes = ArrayList<String>()
        val total = plan.runs
        val single = plan.keys.size == 1 && total == 1
        val both = plan.keys.size > 1
        legs@ for (key in plan.keys) {
            var legDone = 0
            if (both) MacroLog.i("${spec.name}: ${if (total == Int.MAX_VALUE) "all" else "$total"} run(s) with $key keys")
            while (legDone < total) {
                val count = if (total == Int.MAX_VALUE) "" else "/$total"
                if (!single) MacroLog.status("${spec.name}: ${if (both) "$key key " else ""}run ${legDone + 1}$count")
                val r = Run(ctx, spec, key).also { it.ignoreHeldKey = ignore }
                Route.cameraLags = spec.cameraLags
                val outcome = try { runInner(r) } catch (s: StopScenario) { s.message ?: "stopped" } finally { AreaNav.align = null; Route.cameraLags = false; Route.loose = false }
                if (r.outOfKeys) {
                    // this colour is used up: on to the next one (never buy)
                    notes += outcome
                    MacroLog.i("${spec.name}: $outcome after $legDone $key run(s)")
                    continue@legs
                }
                if (r.heldKey && !r.entered) {
                    // a White Key from before: deal with it first, then this run
                    when (onWhiteKey) {
                        "phantom" -> { if (!phantom(ctx, lines)) { end = "the Phantom Crystal Dimension run failed"; break@legs }; phantoms++; continue }
                        "stop" -> { end = outcome; break@legs }
                        else -> { ignore = true; continue }
                    }
                }
                val ok = outcome.startsWith("${spec.name} done") || r.phantom
                // a run that stopped before entering (or skipping) isn't a run
                if (!ok && !r.entered && !r.skipped) { end = if (done > 0) "stopped after run $done: $outcome" else outcome; break@legs }
                lines += "run ${done + 1} ($key key): " + report(r, outcome)
                done++
                legDone++
                ctx.count("runs")
                ctx.count("runs_$key")
                if (!ok) { end = "stopped after run $done: $outcome"; break@legs }
                if (r.phantom) when (onWhiteKey) {
                    "phantom" -> { if (!phantom(ctx, lines)) { end = "the Phantom Crystal Dimension run failed"; break@legs }; phantoms++ }
                    "stop" -> { end = "a White Key dropped: stopping for you (Phantom Crystal Dimension open)"; break@legs }
                    else -> ignore = true
                }
            }
        }
        if (end.isEmpty() && notes.isNotEmpty()) end = notes.joinToString("; ")
        if (single && lines.size == 1 && phantoms == 0 && end.isEmpty()) return lines[0].substringAfter(": ")
        if (single && lines.isEmpty() && phantoms == 0) return end
        val mins = (System.currentTimeMillis() - start) / 60000
        val perKey = if (both) " (" + plan.keys.joinToString { k -> "${ctx.stats["runs_$k"] ?: 0} $k" } + ")" else ""
        val summary = "${spec.name}: $done run(s)$perKey" + (if (phantoms > 0) ", $phantoms Phantom Crystal Dimension run(s)" else "") +
            " in ${mins}m" + (if (end.isNotEmpty()) "; $end" else "")
        lines.forEach { MacroLog.i(it) }
        MacroLog.status(summary)
        return summary
    }

    /** The Phantom Crystal Dimension, from next to the door; back to the loop afterwards. */
    private fun phantom(ctx: Context, lines: MutableList<String>): Boolean {
        MacroLog.status("White Key: on to the Phantom Crystal Dimension")
        val out = PhantomCrystal.run(ctx)
        lines += "Phantom Crystal Dimension: $out"
        return out.startsWith("Phantom Crystal Dimension done")
    }

    private fun runInner(r: Run): String {
        val ctx = r.ctx
        val spec = r.spec
        MacroLog.i("${spec.name}: key ${r.key}, cat ticket ${ctx.bool("cat_ticket")}, " +
            "mobs ${r.mobs}, boss ${r.bossSetup}")
        // started on the boss gate's question, or outside: else already inside. A dungeon's first
        // map can be a town also open outside a run (Underworld's Nazrik): inside, the field's bar
        // has only Menu and Rewards
        if (!bossGate(r) && (whereAmI(r) == null || !PhantomCrystal.inside(ctx)) && !Battle.inBattle(ctx)) {
            enter(r)?.let { return it }
        }
        repeat(300) {
            if (r.phantom) return phantomDone(r)
            // the boss was beaten in a fight that ended the last step early (e.g. its question met
            // while not on the map's own steps): the run is over
            if (r.bossBeaten) return finish(r)
            val idx = r.nextMap ?: if (r.map >= 0 && !r.moved) r.map else whereAmI(r)
            r.moved = false
            r.nextMap = null
            if (idx == null) {
                if (fight(r)) return@repeat
                if (bossGate(r)) return@repeat
                // talk on screen hides the map title: tap through it, the run goes on
                if (!r.bossBeaten && !r.bossNext && talkThrough(r)) return@repeat
                // the boss not beaten yet: the map's title can still be coming in (just arrived, a
                // fade): look again a few times before taking the run as over
                if (!r.bossBeaten && r.notOnAMap < 5) { r.notOnAMap++; ctx.wait(1.5); return@repeat }
                // not on one of the dungeon's maps any more: the run ended
                return finish(r)
            }
            r.notOnAMap = 0
            if (idx != r.map) {
                AreaNav.align = if (spec.areaMapScrolls || spec.maps[idx].scrolls) spec.maps[idx].layout else null
                r.live = null; r.expect = null; r.portalAt = null
                val path = spec.maps[idx].path
                val arrival = spec.maps[idx].arrival
                if (path != null && arrival != null) {
                    // a fixed path: no look at the area map, the party starts where it arrives
                    r.shift = 0.0
                    r.pos = arrival; r.planPos = arrival
                    r.order = path.mapNotNull { it.chest }
                    MacroLog.i("following the path; chest order: ${r.order.map { it + 1 }}")
                } else {
                    // line the layout up with where the party arrived (only when it just came in)
                    val party = arrival?.let { a -> AreaNav.look(ctx) { emptyList() }.candidates.minByOrNull { abs(it.x - a.x) + 3 * abs(it.y - a.y) } }
                    // a map with a layout but no arrival yet: where the party came in, for its fixed path
                    // (copy into the dungeon data as `arrival`, then `another-macro dungeon-routes --write`)
                    if (arrival == null) spec.maps[idx].layout?.let { l ->
                        val seen = AreaNav.look(ctx) { emptyList() }.party
                        val seg = seen?.let { Route.segOf(l, it) }
                        MacroLog.i("ARRIVAL draft for map ${idx + 1} (${spec.maps[idx].names.first()}): " +
                            if (seen != null && seg != null) "arrival: [${"%.3f".format(seen.x.coerceIn(seg.x1, seg.x2))}, ${"%.3f".format(seg.y)}]"
                            else "party not seen on a street (${seen ?: "no marker"})")
                    }
                    r.shift = if (arrival != null && party != null && abs(party.y - arrival.y) < AreaNav.ROW_TOL && abs(party.x - arrival.x) < 0.025)
                        party.x - arrival.x else 0.0
                    MacroLog.i("layout offset ${"%.3f".format(r.shift)}")
                    r.pos = null; r.planPos = null
                    r.order = chestOrder(spec.maps[idx].shifted(r.shift), party)
                }
                // the path is walked at once and the battles on the way are fought where they come (the
                // walk reads its spot on the area map after each one); the enemies left, if any, after it
                r.map = idx; r.chestsDone = 0; r.legsDone = 0; r.cleared = !spec.maps[idx].enemies
                r.retryLater.clear(); r.retried.clear()
                r.onBossMap = spec.maps[idx].boss
                r.chestsTotal += spec.maps[idx].chests.size.takeIf { it > 0 } ?: spec.maps[idx].chestsCount
                task(r, "Map ${idx + 1}/${spec.maps.size}: ${spec.maps[idx].names.first()}")
                ctx.count("maps")
            }
            val ms = spec.maps[idx].shifted(r.shift)
            // where the time goes (the app's Statistics): each map, and each stop on it
            val mapPart = ctx.timings.begin("map ${idx + 1}")
            try { when {
                ms.steps != null -> ctx.timed("steps") { runSteps(r, ms) }
                ms.layout == null -> ctx.timed("sweep") { sweep(r) }
                // sparkles that move every run: read where they are on arrival, then each in turn
                ms.sparkleIcons && ms.layout != null && r.live == null && !collects(ctx, "sparkles") && ms.portal == null -> r.live = mutableListOf()
                ms.sparkleIcons && ms.layout != null && r.live == null -> ctx.timed("read sparkles") { readSparkles(r, ms) }
                ms.sparkleIcons && ms.layout != null && r.live!!.isNotEmpty() -> ctx.timed("sparkle") { takeSparkleAt(r, ms, r.live!!.removeAt(0)) }
                // on a path: its legs in order (chests, sparkles), then the exit
                ms.path != null && r.pos != null && r.legsDone < ms.path.size && ms.path[r.legsDone].target != "exit" -> {
                    val leg = ms.path[r.legsDone]
                    val off = (leg.sparkle != null && !collects(ctx, "sparkles")) || (leg.fish != null && !collects(ctx, "fish")) ||
                        (leg.jar != null && !collects(ctx, "jars"))
                    ctx.timed(leg.target) { when {
                        off -> { MacroLog.i("${leg.target}: left out (its option is off)"); r.offPlan = true }
                        leg.chest != null && leg.chest < ms.chests.size -> takeChest(r, ms, ms.chests[leg.chest])
                        leg.sparkle != null && leg.sparkle < ms.sparkles.size -> takeSparkle(r, ms, leg, ms.sparkles[leg.sparkle])
                        leg.fish != null && leg.fish < ms.fish.size -> takeFish(r, ms, leg, ms.fish[leg.fish])
                        leg.jar != null && leg.jar < ms.jars.size -> takeJar(r, ms, leg, ms.jars[leg.jar])
                        else -> MacroLog.i("path leg ${leg.target}: nothing to do")
                    } }
                    r.legsDone++
                }
                r.chestsDone < ms.chests.size -> ctx.timed("chest ${r.chestsDone + 1}") { takeChest(r, ms, ms.chests[r.order.getOrElse(r.chestsDone) { r.chestsDone }]) }
                // the rewards first: a chest missed on the way gets a second, slower try by the area map
                r.retryLater.isNotEmpty() && ms.layout != null -> ctx.timed("chest second try") { retryChest(r, ms, r.retryLater.removeAt(0)) }
                // every stop done: the enemies still left, when the map's can run out and the user wants them all
                !r.cleared && !r.onBossMap && ms.exit != null && defeatAll(ctx) -> {
                    // a chest search or a battle can have moved the party: its spot first (the back and forth stays on its street)
                    val l = ms.layout
                    if (r.unsure && l != null) r.pos?.let { p -> readSpot(r, l, p)?.let { r.pos = it; r.planPos = it } }
                    ctx.timed("defeat all") { grind(r, ms) }
                    // the fights moved the party a little: read its spot once before the exit
                    if (r.pos != null) { r.unsure = true; r.lastDir = 0.0 }
                }
                ms.exit != null -> ctx.timed("exit") { leave(r, ms, idx) }
                else -> ctx.timed("sweep") { sweep(r) }
            } } finally { ctx.timings.end(mapPart) }
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

    /**
     * The dungeon panel's counters, left to right. One OCR line can hold two of them
     * ("5/5 En 16/50"): lines go by x, then the counters in a line by their order in it.
     */
    /**
     * The character the "please include" box names. A name from the dungeon's `requires` list that
     * the screen shows wins (OCR can mangle a name); else what follows "include:" on the same line,
     * when it reads like a name; else the `requires` list itself; null when there's nothing to go by.
     */
    internal fun missingMember(lines: List<Ocr.Line>, requires: List<String>): String? {
        val ordered = lines.sortedWith(compareBy({ (it.center.y * 25).toInt() }, { it.center.x }))
        val all = Ocr.norm(ordered.joinToString(" ") { it.text })
        requires.filter { Ocr.norm(it).isNotEmpty() && Ocr.norm(it) in all }.takeIf { it.isNotEmpty() }?.let { return it.joinToString(" and ") }
        val i = ordered.indexOfFirst { Ocr.norm(it.text).contains("please include") }
        if (i >= 0) {
            // only a name on the same line: the "next" OCR line can be a party member or the screen's
            // title, not the name asked for
            val raw = ordered[i].text.substringAfter(":", "").trim()
            val name = raw.replace(Regex("""[^\p{L}'\-. ]"""), " ").trim().replace(Regex("\\s+"), " ")
            val n = Ocr.norm(name)
            // the box's buttons and the key line aren't names
            if (name.count { it.isLetter() } >= 2 && n !in setOf("back", "move", "skip", "ok", "cancel") &&
                !n.startsWith("using") && "key" !in n.split(" ") && name.split(" ").size <= 4) return name
        }
        return requires.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    }

    /**
     * Close the Another Dungeon panel and map back to the field. A place's box can still be open
     * over the panel, so the close misses it: Back until the screen's title is gone.
     */
    private fun closePanel(ctx: Context) {
        ctx.tap(AD_CLOSE); ctx.wait(1.5)
        repeat(3) {
            if (ctx.readText(Region(0.3, 0.0, 0.4, 0.08)).none { Ocr.norm(it.text).contains("another dungeon") }) return
            MacroLog.i("still on the Another Dungeon screen: Back")
            ctx.back(); ctx.wait(1.2)
        }
    }

    /** The chosen key's count and the Cat Express Tickets' count from [panelCounters]; null when not read. */
    internal fun keysAndTickets(counters: List<List<Int>>, red: Boolean): Pair<Int?, Int?> {
        val tickets = counters.firstOrNull { it[1] == 50 }?.get(0)
        val keys = if (red) counters.firstOrNull { it[1] == 5 }?.get(0)
            else counters.firstOrNull { it[1] != 5 && it[1] != 50 }?.get(0)
        return keys to tickets
    }

    internal fun panelCounters(lines: List<Ocr.Line>): List<List<Int>> =
        lines.sortedBy { it.center.x }.flatMap { l ->
            // the denominators are 50, 10 or one digit: "5/515/50" (no space read) is 5/5 and 15/50
            Regex("""(\d{1,2})\s*/\s*(50|10|[1-9])(?=\D|$|\d{1,2}\s*/)""").findAll(l.text.replace(Regex("[Oo]"), "0").replace(Regex("[Il|]"), "1"))
                .map { m -> m.groupValues.drop(1).map { v -> v.toInt() } }.toList()
        }

    /**
     * An item name as read by OCR, cleaned: "LOw Class Scroll" → "Low Class Scroll". Null for junk
     * ("X4( X400") with no word of three letters or more.
     */
    internal fun cleanName(raw: String): String? {
        val words = raw.replace(Regex("""[^A-Za-z'\- ]"""), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.none { w -> w.count { it.isLetter() } >= 3 }) return null
        return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }

    /**
     * "Obtained Dark Residue x200." → items. The banner can come as several OCR lines and with
     * junk ("Obtained Dark Residue x e x800", "Obtained Dark Residue Dark Residue x800"): the
     * lines are read together, the count is the first "x<number>", the name the words before it.
     */
    internal fun obtained(lines: List<Ocr.Line>): List<Pair<String, Int>> {
        val text = lines.sortedWith(compareBy({ (it.center.y * 25).toInt() }, { it.center.x })).joinToString(" ") { it.text }
        val out = ArrayList<Pair<String, Int>>()
        for (m in Regex("""Obtained\s+(.+?)\s*[x×X]\s*([0-9OoIl,]*[0-9][0-9OoIl,]*)""").findAll(text)) {
            val n = m.groupValues[2].replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(",", "").toIntOrNull() ?: continue
            // drop one-letter junk ("x e"), then a name read twice ("Dark Residue Dark Residue")
            var words = m.groupValues[1].replace(Regex("""[^A-Za-z'\- ]"""), " ").trim().split(Regex("\\s+")).filter { it.length >= 2 }
            if (words.size % 2 == 0 && words.size >= 2 && words.subList(0, words.size / 2).map { it.lowercase() } == words.subList(words.size / 2, words.size).map { it.lowercase() })
                words = words.subList(0, words.size / 2)
            val name = cleanName(words.joinToString(" ")) ?: continue
            out += name to n
        }
        return out
    }

    /** The name already counted that [name] is an OCR variant of ("Medium Class Soroll" → "Medium Class Scroll"), else [name]. */
    internal fun sameName(seen: Map<String, Int>, name: String): String =
        seen.keys.filter { Ocr.similarity(it, name) >= 0.85 }.maxByOrNull { Ocr.similarity(it, name) } ?: name

    private fun noteItems(r: Run, lines: List<Ocr.Line>) {
        for ((raw, n) in obtained(lines)) { val name = sameName(r.items, raw); r.items[name] = (r.items[name] ?: 0) + n }
    }

    /** The screen is one of the end screens that list rewards. */
    private fun rewardScreen(lines: List<Ocr.Line>): Boolean {
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        return "congratulations" in all || "items obtained" in all
    }

    /**
     * The end screens: "Congratulations" rows ("Medium Class Scroll  × 2", the count read as its own
     * line or on the name's line) and "Items Obtained" ("Dark Residue / Amount 4,250 / Held …").
     * Returns how many rewards were read off this screen.
     */
    private fun noteRewards(r: Run, lines: List<Ocr.Line>): Int {
        fun num(t: String) = t.replace(Regex("[Oo]"), "0").replace(Regex("[Il]"), "1").replace(Regex("[^0-9]"), "").toIntOrNull()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        var read = 0
        if ("congratulations" in all) {
            val count = Regex("""^\s*[x×X]\s*([0-9OoIl,]+)\s*$""")
            val inline = Regex("""^(.*[A-Za-z]{3}.*?)\s*[x×X]\s*([0-9OoIl,]+)\s*$""")
            for (c in lines) {
                val (name, n) = if (count.matches(c.text)) {
                    // the name to the left on the same row, else just above or below the count (icon grids)
                    val left = lines.filter { it !== c && abs(it.center.y - c.center.y) < 0.03 && it.center.x < c.center.x && Regex("[A-Za-z]{3}").containsMatchIn(it.text) }
                        .maxByOrNull { it.center.x }
                    val near = left ?: lines.filter { it !== c && abs(it.center.x - c.center.x) < 0.12 && abs(it.center.y - c.center.y) < 0.08 &&
                        Regex("[A-Za-z]{3}").containsMatchIn(it.text) && "congratulations" !in Ocr.norm(it.text) }
                        .minByOrNull { abs(it.center.y - c.center.y) }
                    (near?.text?.let { cleanName(it) } ?: continue) to (num(c.text) ?: continue)
                } else {
                    val m = inline.find(c.text) ?: continue
                    (cleanName(m.groupValues[1]) ?: continue) to (num(m.groupValues[2]) ?: continue)
                }
                val key = sameName(r.rewards, name)
                r.rewards[key] = (r.rewards[key] ?: 0) + n
                read++
            }
        }
        if ("items obtained" in all) {
            for (a in lines.filter { Ocr.norm(it.text).startsWith("amount") }) {
                val sameRow = lines.filter { abs(it.center.y - a.center.y) < 0.03 }.sortedBy { it.center.x }.joinToString(" ") { it.text }
                val n = Regex("""[Aa]mount\s*([0-9OoIl,]+)""").find(sameRow)?.groupValues?.get(1)?.let { num(it) } ?: continue
                val name = lines.filter { it.center.y < a.center.y && a.center.y - it.center.y < 0.1 && abs(it.center.x - a.center.x) < 0.2 &&
                    Regex("[A-Za-z]{3}").containsMatchIn(it.text) && !Regex("[0-9]").containsMatchIn(it.text) && !Ocr.norm(it.text).startsWith("items") }
                    .maxByOrNull { it.center.y }?.text?.let { cleanName(it) } ?: continue
                r.rewards[sameName(r.rewards, name)] = n // the run's total of that item (shown once)
                read++
            }
        }
        return read
    }

    /** One line about the run: how long, what it got. Also appended to the app's runs.log. */
    private fun report(r: Run, outcome: String): String {
        val secs = (System.currentTimeMillis() - r.start) / 1000
        // this run's counts (the macro's stats add up over the loop)
        val st = r.ctx.stats.mapValues { (k, v) -> v - (r.base[k] ?: 0) }
        val items = r.items.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val rewards = r.rewards.entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "none read" }
        val text = "$outcome — ${secs / 60}m ${secs % 60}s; " +
            (if (r.skipped) "skipped with a Cat Express Ticket; " else "chests ${st["chests"] ?: 0}/${r.chestsTotal}" +
                (if (r.missed.isNotEmpty()) " (missed: ${r.missed.joinToString("; ")})" else "") + ", battles ${st["battles"] ?: 0}; ") +
            (if (r.trips.isNotEmpty()) "${r.trips.joinToString("; ")}; " else "") +
            (if (r.searched.isNotEmpty()) "off the fixed path (walked by the area map): ${r.searched.distinct().joinToString("; ")}; " else "") +
            (if (r.skipped) "" else "chest items: $items; ") + "rewards: $rewards; White Key: ${if (r.phantom) "yes" else "no"}" +
            timeByKind(r).takeIf { it.isNotEmpty() }?.let { k -> "; time: " + k.take(8).joinToString(", ") { "${it.first} ${it.second / 1000}s" } }.orEmpty()
        runCatching {
            val o = org.json.JSONObject().put("when", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.start)))
                .put("dungeon", r.spec.name).put("key", r.key).put("seconds", secs).put("outcome", outcome)
                .put("skipped", r.skipped).put("chests", st["chests"] ?: 0).put("chests_total", r.chestsTotal)
                .put("missed", org.json.JSONArray(r.missed)).put("searched", org.json.JSONArray(r.searched.distinct())).put("trips", org.json.JSONArray(r.trips)).put("battles", st["battles"] ?: 0).put("white_key", r.phantom).put("items", org.json.JSONObject(r.items as Map<*, *>))
                .put("time_s", org.json.JSONObject(timeByKind(r).associate { it.first to it.second / 1000 } as Map<*, *>)).put("rewards", org.json.JSONObject(r.rewards as Map<*, *>))
            java.io.File(r.ctx.debugDir.parentFile, "runs.log").appendText(o.toString() + "\n")
        }.onFailure { MacroLog.e("could not save the run report", it) }
        MacroLog.status(text)
        return text
    }

    /**
     * Where this run's time went by kind of part, longest first, in ms: every timed part's own time
     * (without the parts inside it) added up by its name, numbers left out ("fish 2" → "fish"; the
     * dungeon step's own time, between the timed parts, is "other").
     */
    private fun timeByKind(r: Run): List<Pair<String, Long>> = timeByKind(r.ctx.timings.since(r.timesBase))

    internal fun timeByKind(times: Map<String, Timings.Entry>): List<Pair<String, Long>> {
        val out = LinkedHashMap<String, Long>()
        for (path in times.keys) {
            val name = path.substringAfterLast(Timings.SEP)
            // the parts above the dungeon step (the screen handler) hold no time of their own here
            if (name.startsWith("on ") || name == "map") continue
            val kind = when {
                Regex("^map \\d+$").matches(name) || name == "another_dungeon" -> "other"
                else -> name.replace(Regex("\\s*\\d+$"), "")
            }
            val own = Timings.own(times, path)
            if (own > 0) out[kind] = (out[kind] ?: 0L) + own
        }
        return out.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun phantomDone(r: Run) = "${r.spec.name} done: the Phantom Crystal Dimension is open (White Key)"

    // --- 1. entering ------------------------------------------------------------------
    /** Through the Another Dungeon door. A message when the run ends here (skipped), else null. */
    private fun enter(r: Run): String? = r.ctx.timed("enter") { enterInner(r) }

    private fun enterInner(r: Run): String? {
        val ctx = r.ctx
        val red = r.key == "red"
        val difficulty = if (red) "Very Hard" else "Hard"
        if (!waitForField(ctx)) ctx.stop("not on the field: start on the field, or next to the Another Dungeon door in the Spacetime Rift")
        task(r, "Entering ${r.spec.name} ($difficulty)")
        // the door's "!", or (option go_to_door, on by default) the trip to it from anywhere on the field
        RiftDoor.open(ctx)
        if (!r.ignoreHeldKey && phantomTabGlows(ctx)) {
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            r.heldKey = true
            return "a White Key is held (the Phantom Crystal Dimension is open): not entering"
        }
        // the "???" tab has no text OCR can read: tap it by its place
        if (r.spec.era == "???") ctx.tap(AD_OTHER_TAB) else tapText(r, eraLabel(r.spec.era))
        ctx.wait(1.5)
        // a dungeon inside a place of the era's map: open that place's map first (a box over the
        // map; the label can sit right of the middle there)
        val area = r.spec.area
        if (area != null) {
            // (0.8: how far left the Present map can pan that label)
            mapLabel(r, arrayOf(area), 0.8) ?: ctx.stop("$area not on the ${r.spec.era} map: ${seen(ctx)}")
            ctx.wait(1.5)
        }
        mapLabel(r, listOfNotNull(r.spec.name, r.spec.label).toTypedArray(), if (area != null) 0.85 else 0.62)
            ?: ctx.stop("${r.spec.name} not on the ${r.spec.era} map${area?.let { " ($it)" } ?: ""}: ${seen(ctx)}")
        ctx.wait(2.0)
        // the panel's counters at the bottom, left to right: green keys, red keys, Cat Express Tickets
        val bottom = LiveLog.without { ctx.readText() }.filter { it.center.y > 0.85 && it.center.x > 0.5 }
        // OCR reads 0 as O at times ("16/5O")
        val counters = panelCounters(bottom)
        // each counter by its denominator, never by its place (a counter OCR missed shifts the rest):
        // red keys "/5", Cat Express Tickets "/50", green keys the other one
        val (keys, tickets) = keysAndTickets(counters, red)
        if (keys == null || tickets == null || counters.size < 3) MacroLog.i("panel counters read: ${bottom.joinToString(" | ") { it.text }} → $counters")
        MacroLog.i("keys (${if (red) "red" else "green"}): ${keys ?: "not read"}; Cat Express Tickets: ${tickets ?: "not read"}")
        if (keys != null && keys < r.spec.keyCost) {
            // the panel offers to buy keys: never; the loop ends here
            closePanel(ctx)
            r.outOfKeys = true
            return "out of ${if (red) "red" else "green"} keys" + (if (r.spec.keyCost > 1) " ($keys left, a run takes ${r.spec.keyCost})" else "")
        }
        val skip = ctx.bool("cat_ticket") && (tickets ?: 0) > 0
        if (ctx.bool("cat_ticket") && !skip) MacroLog.i("no Cat Express Ticket to use: walking the dungeon instead")
        tapText(r, difficulty, exact = true, minX = 0.6) ?: ctx.stop("no '$difficulty' entry for ${r.spec.name}: ${seen(ctx)}")
        ctx.wait(2.0)
        // "Using Green Key×1 to move to <dungeon> (Hard)."
        val lines = ctx.readText()
        // "In order to move to <dungeon> (Hard) please include: <a character>": Move stays greyed out
        if (lines.any { Ocr.norm(it.text).contains("please include") }) {
            val who = missingMember(lines, r.spec.requires)
            MacroLog.i("the game asks for a character: ${lines.joinToString(" | ") { it.text }} → ${who ?: "not read"}")
            // the box's Back, then the dungeon panel and the era map: the run leaves the field as it found it
            tapText(r, "Back", exact = true)
            ctx.wait(1.0)
            closePanel(ctx)
            val party = Party.number(ctx.params["party"])?.let { "party $it" } ?: "the party in front"
            ctx.stop(if (who != null) "${r.spec.name} needs $who in the party, and $party doesn't have $who: " +
                    "add $who to it (or pick a party with $who), then start again"
                else "${r.spec.name} needs a character $party doesn't have (the game's box named them, OCR couldn't read it): " +
                    "add them to the party, then start again")
        }
        val using = lines.firstOrNull { Ocr.norm(it.text).startsWith("using") }?.text ?: ""
        val keyWord = if (red) "red" else "green"
        if (!Ocr.norm(using).contains(keyWord)) {
            tapText(r, "Back", exact = true)
            ctx.stop("expected a $keyWord key for $difficulty but the game says: '$using'")
        }
        if (skip) {
            tapText(r, "Skip", exact = true) ?: ctx.stop("no Skip button (Cat Express Ticket): ${seen(ctx)}")
            // Skip has no confirmation: straight to "Congratulations" and the items
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
            talkThrough(r)
        }
        if (!waitForField(ctx, 20)) ctx.stop("didn't arrive in ${r.spec.name}: ${seen(ctx)}")
        return null
    }

    /**
     * Tap a place's label on the Another Dungeon map. The map keeps its last pan, so a label can sit
     * half hidden under the era icons at the top right or cut by the left edge: then drag what shows
     * of it (else the map) toward the middle and look again.
     */
    private fun mapLabel(r: Run, wants: Array<String>, maxX: Double): Ocr.Line? {
        val ctx = r.ctx
        tapText(r, *wants, minY = 0.2, maxX = maxX, tries = 2)?.let { return it }
        val words = Ocr.norm(wants.first()).split(" ").filter { it.length >= 4 }
        // a few drags: the map keeps its last pan, so the label can be off either side or at the
        // right edge under this app's floating buttons. When part of it reads, drag that part to the
        // middle; else drag blind: left once, then right three times (back to the start and on past
        // it), so a label off either side comes into view
        val blind = listOf(-1, 1, 1, 1)
        for (attempt in blind.indices) {
            val part = ctx.readText().firstOrNull { l -> val t = Ocr.norm(l.text); words.isNotEmpty() && words.count { it in t } >= (words.size + 1) / 2 }
            MacroLog.i("${wants.first()} label not in the clear (${part?.text ?: "not seen"}): dragging the map")
            // blind drags start over open sea (a drag that starts on the middle islands doesn't pan the
            // map); each moves it about a third of the screen (a part cut by the top edge sits on the
            // map's title bar: the drag starts lower, same way and length)
            if (part != null) {
                val from = Point(part.center.x.coerceIn(0.05, 0.9), part.center.y.coerceAtLeast(0.2))
                ctx.drag(from, Point(0.45 + from.x - part.center.x, 0.5 + from.y - part.center.y), 700)
            }
            else if (blind[attempt] < 0) ctx.drag(Point(0.6, 0.43), Point(0.25, 0.5), 700)
            else ctx.drag(Point(0.25, 0.43), Point(0.6, 0.5), 700)
            ctx.wait(1.2)
            tapText(r, *wants, minY = 0.2, maxX = maxX, tries = 1)?.let { return it }
        }
        return null
    }

    private fun eraLabel(era: String) = when (era.lowercase()) { "present" -> "Pres."; "antiquity" -> "Antiq."; "future" -> "Fut."; else -> era }

    /** A dialog over the field: the free daily ad ("Watch Video"), thank-you / OK / Close. */
    private fun clearPopup(r: Run) {
        tapText(r, "Watch Video", "Close", "OK", exact = true, tries = 1)
        r.ctx.wait(3.0)
    }

    // --- 2-4. inside --------------------------------------------------------------------
    /** Which of the dungeon's maps the area map names (its title "<dungeon> - <map>"), or null. */
    private fun whereAmI(r: Run): Int? {
        val ctx = r.ctx
        if (!waitForField(ctx, 10) && !(talkThrough(r) && waitForField(ctx, 6))) return null
        ctx.tap(AreaNav.minimap(ctx)); ctx.wait(1.5)
        ctx.refresh()
        if (Battle.inBattle(ctx)) return null // a battle started: no close tap (it would hit a portrait)
        val text = ctx.readText()
        ctx.tap(Point(0.5, 0.93)); ctx.wait(1.0)
        val title = text.filter { it.center.y < 0.12 }.joinToString(" ") { it.text }
        // "<dungeon> - <map>": the dungeon's name, then the map's. A dungeon whose maps carry their own
        // titles: the whole title must read as one of the map names
        val named = Ocr.norm(title).contains(Ocr.norm(r.spec.name))
        val part = if (named) title.substringAfter("-").trim() else title.trim()
        fun sim(i: Int) = r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) }
        val best = r.spec.maps.indices.maxByOrNull { sim(it) } ?: return null
        if (sim(best) <= (if (named) 0.6 else 0.85)) return null
        // two maps with the same title: the one an exit was just taken toward, else the one the party is on
        val tied = r.spec.maps.indices.filter { sim(it) >= sim(best) - 0.02 }
        return when {
            tied.size < 2 -> best
            r.expect in tied -> r.expect
            r.map in tied -> r.map
            else -> best
        }
    }

    /** Where a map's title shows as it opens. */
    private val ARRIVAL_TITLE = Region(0.15, 0.05, 0.7, 0.2)

    /**
     * Watch the arrival title for up to [seconds]: the index of a map other than [from] that it
     * names, or null. Only a whole-title match counts (map titles can share most of their words).
     */
    private fun arrivalTitle(r: Run, from: Int, seconds: Double): Int? {
        val ctx = r.ctx
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (Battle.inBattle(ctx)) return null
            val lines = ctx.readText(ARRIVAL_TITLE).map { it.text }.filter { Ocr.norm(it) != "hard" && it.length > 4 }
            for (t in lines) {
                val named = Ocr.norm(t).contains(Ocr.norm(r.spec.name))
                val part = if (named) t.substringAfter("-").trim() else t.trim()
                val best = r.spec.maps.indices.maxByOrNull { i -> r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) } } ?: continue
                if (best != from && r.spec.maps[best].names.maxOf { Ocr.similarity(part, it) } > 0.9) return best
            }
            ctx.wait(0.3)
        }
        return null
    }

    /** Fight what interrupted a walk; true if there was a battle. */
    private fun onStep(r: Run): () -> Boolean = { fight(r) }

    /** The walk table for [ms] (walk.yaml scaled by the map's walk_scale). */
    private fun table(ctx: Context, ms: MapSpec): Route.Table = if (ms.walkScale == 1.0) Route.table(ctx) else CatRoute.table(ctx, ms.walkScale)

    /** A walk by the area map (a look after every move) instead of fixed steps: noted for the run's report. */
    private fun searchWalk(r: Run, l: AreaNav.Layout, goal: Point, what: String): Boolean {
        r.searched += "map ${r.map + 1}: $what"
        r.ctx.count("area_map_walks")
        return r.ctx.timed("off path: walk by the area map") { AreaNav.walkLayout(r.ctx, l, goal, onStep(r)) }
    }

    /**
     * The party's spot on the area map: the marker-like blob on a street nearest to [near] (the
     * followed position). Gold chest icons look like the marker too, so the most marker-like blob
     * isn't taken blindly.
     */
    private fun readSpot(r: Run, l: AreaNav.Layout, near: Point): Point? {
        val seen = AreaNav.look(r.ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
            .minByOrNull { abs(it.x - near.x) + 2 * abs(it.y - near.y) } ?: return null
        val seg = Route.segOf(l, seen)!!
        return Point(seen.x.coerceIn(seg.x1, seg.x2), seg.y)
    }

    private fun takeChest(r: Run, ms: MapSpec, chest: Point) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: chest ${r.chestsDone + 1}/${ms.chests.size}")
        MacroLog.i("chest ${r.chestsDone + 1}/${ms.chests.size} at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)})")
        val leg = ms.path?.firstOrNull { it.chest != null && ms.chests.getOrNull(it.chest) == chest }
        val party = if (leg != null && r.pos != null) {
            walkLeg(r, ms, leg, chest)
            r.pos
        } else {
            searchWalk(r, ms.layout!!, chest, "chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}) (no fixed path)")
            AreaNav.look(ctx) { emptyList() }.party
        }
        // test hook (never in a macro's options): param test_miss_chest = "map.chest" (1-based, in the
        // order taken) treats that chest's first try as missed, to exercise the second try live
        if (chest !in r.retried && ms.layout != null &&
            ctx.params["test_miss_chest"]?.trim() == "${r.map + 1}.${r.chestsDone + 1}") {
            MacroLog.i("test: treating chest ${r.chestsDone + 1} on map ${r.map + 1} as missed on its first try")
            r.retryLater += chest
            r.unsure = r.pos != null
            r.chestsDone++
            return
        }
        // close by: the chest's "!" shows; look around the spot, a little further each side, until it does
        val dir = if (party != null && chest.x < party.x) -1.0 else 1.0
        // the "!" pops up a while after the party stops: watch for it before looking around
        if (waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); return }
        // from here on the chest is looked for around its spot (a fallback): the run's report says so
        r.searched += "map ${r.map + 1}: looked for the chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}) around its spot (no \"!\" there)"
        ctx.count("chest_lookarounds")
        // not there: read the real spot on the map and walk to the chest's x from it (a hold can
        // come up short, a battle can cut it), then look again
        val l0 = ms.layout
        val p0 = r.pos
        if (l0 != null && p0 != null) {
            val t = table(ctx, ms)
            repeat(4) {
                // a battle can start just as the party stops by the chest (no "!" then, and no area
                // map to read): fight it first, then look for the "!" again
                if (fight(r) && waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); return }
                val at = readSpot(r, l0, r.pos ?: p0) ?: return@repeat
                MacroLog.i("no \"!\" yet: the map shows the party at (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)}), chest at ${"%.3f".format(chest.x)}")
                r.pos = at
                val d = chest.x - at.x
                if (Route.segOf(l0, at) != Route.segOf(l0, chest)) {
                    // on another street (stairs missed): walk there by the area map, then look again
                    MacroLog.i("not on the chest's street: walking there by the area map")
                    searchWalk(r, l0, chest, "chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}) (off its street)")
                    r.pos = Point(chest.x, Route.segOf(l0, chest)?.y ?: chest.y)
                    if (waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); r.unsure = true; return }
                    return@repeat
                }
                if (abs(d) < 0.004) return@repeat
                // a read a little behind the way the party walked is a wobbly marker: don't turn back for it
                if (r.lastDir != 0.0 && d * r.lastDir < 0 && abs(d) <= 0.01) return@repeat
                r.pos = Route.hold(ctx, l0, t, at, d > 0, t.holdMs(abs(d)).toLong()) { fight(r) }
                if (waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); return }
            }
        }
        // looking around moves the party off the chest's spot: read it on the map before the next leg
        r.unsure = r.pos != null
        // forward first (the way the party was walking: the chest is usually just ahead), then back past the spot
        val fwd = if (r.pos != null && r.lastDir != 0.0) r.lastDir else dir
        val steps = listOf(1, 1, 1, -1, -1, -1, -1, -1, -1)
        for (sgn in steps) {
            val side = fwd * sgn
            val p = r.pos
            if (p != null && ms.layout != null) {
                // (on a path the position is followed through the search too)
                val t = table(ctx, ms)
                r.pos = Route.hold(ctx, ms.layout, t, p, side > 0, t.holdMs(0.006).toLong()) { fight(r) }
            } else AreaNav.hold(ctx, side, 0.006)
            fight(r)
            if (waitBubble(r, 1.5)) { r.chestsDone++; ctx.count("chests"); return }
        }
        // (where the party stands on the map tells whether the chest's spot in the dungeon data is off)
        val at = r.pos?.let { p -> ms.layout?.let { readSpot(r, it, p) } }
        // a picture of the screen for working out why (the app's own storage, never shared)
        runCatching {
            ctx.colorMat()?.let { m ->
                val f = java.io.File(ctx.debugDir, "no-chest-map${r.map + 1}-${r.chestsDone + 1}.png")
                org.opencv.imgcodecs.Imgcodecs.imwrite(f.path, m); m.release()
                MacroLog.i("saved ${f.name} in the app's debug folder")
            }
        }
        if (ms.layout != null && chest !in r.retried) {
            MacroLog.i("no chest found by (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}) yet: trying again before the exit")
            r.retryLater += chest
            r.unsure = r.pos != null
            r.chestsDone++
            return
        }
        r.missed += "map ${r.map + 1} chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)})" +
            (at?.let { ", party at (${"%.3f".format(it.x)}, ${"%.3f".format(it.y)})" } ?: "")
        MacroLog.i("no chest found by (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}); skipping it" +
            (at?.let { " (the map shows the party at (${"%.3f".format(it.x)}, ${"%.3f".format(it.y)}))" } ?: ""))
        r.unsure = r.pos != null
        r.chestsDone++
    }

    /**
     * The second try at a chest missed on the way: walked to by the area map from wherever the
     * party is (looking at it, slower but sure of the spot), then the same look around as the first
     * try. Counted already as tried ([Run.chestsDone]), so only a found one adds to the count.
     */
    private fun retryChest(r: Run, ms: MapSpec, chest: Point) {
        val ctx = r.ctx
        r.retried += chest
        task(r, "Map ${r.map + 1}: second try at a missed chest")
        MacroLog.i("second try at the chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}): by the area map")
        val l = ms.layout!!
        searchWalk(r, l, chest, "second try at the chest at (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)})")
        val seg = Route.segOf(l, chest)
        if (r.pos != null) { r.pos = Point(chest.x, seg?.y ?: chest.y); r.planPos = r.pos; r.lastDir = 0.0 }
        // the path's fixed steps start at the stop before them, not here: the next leg (the exit) goes by the area map
        r.offPlan = true
        if (waitBubble(r, 3.0)) { ctx.count("chests"); ctx.count("chests_second_try"); r.unsure = r.pos != null; return }
        // the same careful look as the first try (reads the spot, walks the rest, looks either side;
        // its walk there is by the area map too, as the party already stands by the spot)
        val before = r.chestsDone
        val found = ctx.stats["chests"] ?: 0
        takeChest(r, ms, chest)
        if ((ctx.stats["chests"] ?: 0) > found) ctx.count("chests_second_try")
        // takeChest counts every chest it was given; this one was counted on the first try
        r.chestsDone = before
        r.offPlan = true
    }

    /**
     * A sparkle stop: walk the path's leg there, then watch a few frames for the sparkle and tap
     * it. A stop without one is fine (not every sparkle shows every run). The party can step
     * toward the sparkle: the spot is read again before the next leg.
     */
    private fun takeSparkle(r: Run, ms: MapSpec, leg: Route.Leg, at: Point) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${leg.target}")
        walkLeg(r, ms, leg, at)
        fight(r)
        pickSparkle(r, ms, at)
    }

    /**
     * On a map with [MapSpec.sparkleIcons]: the bag icons on the area map, each put on the street
     * under it ([SPARKLE_ICON_ABOVE]), ordered for the shortest walk from the party to the exit.
     */
    private fun readSparkles(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        val l = ms.layout!!
        var doors: List<Point> = emptyList()
        val view = AreaNav.look(ctx) { f ->
            if (ms.portal != null) doors = AreaNav.findIcons(ctx, f, DOOR_ICON)
            if (collects(ctx, "sparkles")) AreaNav.findIcons(ctx, f, BAG_ICON) else emptyList()
        }
        val portal = ms.portal?.takeIf { p -> doors.any { abs(it.x - p.sign.x) < 0.03 && abs(it.y - p.sign.y) < 0.04 } }
        if (ms.portal != null) MacroLog.i("${ms.portal.what} portal: ${if (portal != null) "open (its door icon is on the area map)" else "not open"}" +
            " (door icons: ${doors.joinToString { "(${"%.3f".format(it.x)}, ${"%.3f".format(it.y)})" }})")
        val bags = view.targets.mapNotNull { p ->
            val y = p.y + BAG_ABOVE
            val seg = l.segs.filter { abs(it.y - y) < 0.05 && p.x >= it.x1 - 0.02 && p.x <= it.x2 + 0.02 }.minByOrNull { abs(it.y - y) }
            seg?.let { Point(p.x.coerceIn(it.x1, it.x2), it.y) }
        }
        // the portal's spot is one more stop, planned with the sparkles
        val spots = bags.filter { b -> portal == null || abs(b.x - portal.at.x) > 0.01 || abs(b.y - portal.at.y) > 0.01 } + listOfNotNull(portal?.at)
        r.portalAt = portal?.at
        val from: Point? = view.party ?: r.pos ?: ms.arrival
        fun perms(xs: List<Point>): List<List<Point>> = if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> perms(xs - x).map { listOf(x) + it } }
        val start = from
        val order = if (start == null || spots.size > 7) spots else perms(spots).minByOrNull { o ->
            var at: Point = start; var cost = 0.0
            for (q in o) { cost += AreaNav.pathCost(l, at, q); at = q }
            ms.exit?.let { cost += AreaNav.pathCost(l, at, it) }
            cost
        } ?: spots
        MacroLog.i("sparkles on the area map: ${order.joinToString { "(${"%.3f".format(it.x)}, ${"%.3f".format(it.y)})" }}" +
            " (party ${from?.let { "(${"%.3f".format(it.x)}, ${"%.3f".format(it.y)})" } ?: "not seen"})")
        r.live = order.toMutableList()
        // the party's spot from this look: the legs to the sparkles and the exit are walked as fixed steps from it
        view.party?.let { p -> Route.segOf(l, p)?.let { seg -> r.pos = Point(p.x.coerceIn(seg.x1, seg.x2), seg.y); r.planPos = r.pos; r.unsure = false } }
    }

    /** Walk to a sparkle read off the area map and pick it up (or, at the open portal's spot, take its trip). */
    private fun takeSparkleAt(r: Run, ms: MapSpec, at: Point) {
        if (at == r.portalAt && ms.portal != null) { takePortal(r, ms, ms.portal); return }
        task(r, "Map ${r.map + 1}: sparkle at (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)})")
        val leg = plannedLeg(r, ms, at, "sparkle")
        if (leg != null) walkLeg(r, ms, leg, at) else searchWalk(r, ms.layout!!, at, "sparkle (party not seen on the map)")
        fight(r)
        pickSparkle(r, ms, at)
    }

    /** Tap the sparkle by the party (it must stand close: ~0.01 on the area map) and read its banner. */
    private fun pickSparkle(r: Run, ms: MapSpec, at: Point) {
        val ctx = r.ctx
        // up to three taps: a tap that got no banner is tried again; from the 2nd try a sparkle right
        // by the party is first stepped away from, so the tap lands on it and not on the party
        var taps = 0
        // (3 s to see it, then 3 s more after a tap that got no banner)
        var end = System.currentTimeMillis() + 3000
        // on a map with sparkle icons the bright star finder is used: the blob finder can take green
        // scenery and the party for sparkles, and the party isn't always in the middle near a street's end
        val star = ms.sparkleIcons
        var steps = 0
        // none seen by the spot: the party (or the camera) can hide it, so a short step each way and a look again.
        // After [GLANCE_AFTER] empty stops in a row with none picked up this run, a stop gets a glance only
        // (no steps): the map then has none to give (see the knowledge base), and each look-around costs ~9 s
        val picked = (ctx.stats["sparkles"] ?: 0) - (r.base["sparkles"] ?: 0)
        val glance = !star && picked == 0 && r.emptySparkles >= GLANCE_AFTER
        if (glance) { end = System.currentTimeMillis() + 1500; MacroLog.i("sparkle: ${r.emptySparkles} stops in a row had none: a glance only") }
        val looks = if (glance) mutableListOf() else mutableListOf(-1.0, 1.0, 1.0)
        while (taps < 3) {
            if (System.currentTimeMillis() >= end) {
                if (taps > 0 || looks.isEmpty()) break
                val dir = looks.removeAt(0)
                MacroLog.i("no sparkle in sight: a step ${if (dir < 0) "left" else "right"} and a look again")
                // the steps move the party off its followed spot: the next leg reads it on the map first
                r.unsure = true
                ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), SPARKLE_STEP_MS); ctx.wait(0.6)
                fight(r)
                end = System.currentTimeMillis() + 1500
                continue
            }
            val p = if (star) sparkleStar(ctx) else sparkle(ctx)
            if (p == null) { ctx.wait(0.2); continue }
            if (taps > 0 && steps < 2 && abs(p.x - 0.5) < SPARKLE_NEAR) {
                MacroLog.i("sparkle at (${"%.2f".format(p.x)}, ${"%.2f".format(p.y)}) right by the party: a step away first")
                val away = if (p.x < 0.5) 1.0 else -1.0
                r.unsure = true
                ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * away, 0.65), SPARKLE_STEP_MS); ctx.wait(0.6)
                fight(r)
                steps++
                end = maxOf(end, System.currentTimeMillis() + 2500)
                continue
            }
            end = System.currentTimeMillis() + 7000
            taps++
            MacroLog.i("sparkle at (${"%.2f".format(p.x)}, ${"%.2f".format(p.y)})")
            ctx.tapExact(p); ctx.wait(0.5)
            r.unsure = true
            val until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until) {
                val read = ctx.readText(POPUP)
                // ("obtain": OCR reads the banner as "Obtalned" at times)
                if (read.any { val t = Ocr.norm(it.text); "obtain" in t || "obtalned" in t }) {
                    MacroLog.i("sparkle: ${read.joinToString(" ") { it.text }.take(120)}")
                    noteItems(r, read)
                    ctx.count("sparkles")
                    ctx.tap(Point(0.5, 0.75)); ctx.wait(0.5)
                    if (!waitForField(ctx, 6)) { fight(r); return }
                    // the tap walked the party onto the sparkle: it stands at the sparkle's spot, so no area-map
                    // look before the next leg
                    ms.layout?.let { l -> Route.segOf(l, at)?.let { seg ->
                        r.pos = Point(at.x.coerceIn(seg.x1, seg.x2), seg.y); r.planPos = r.pos; r.unsure = false; r.lastDir = 0.0
                    } }
                    return
                }
                ctx.wait(0.2)
            }
            MacroLog.i("tapped the sparkle, no \"Obtained\" banner: ${ctx.readText().joinToString(" | ") { it.text }.take(160)}")
            // a picture of the screen for working out why (the app's own storage, never shared)
            if (taps == 1) runCatching {
                ctx.colorMat()?.let { m ->
                    val f = java.io.File(ctx.debugDir, "no-sparkle-banner-map${r.map + 1}.png")
                    org.opencv.imgcodecs.Imgcodecs.imwrite(f.path, m); m.release()
                    MacroLog.i("saved ${f.name} in the app's debug folder")
                }
            }
            waitForField(ctx, 4)
        }
        if (taps > 0) { r.emptySparkles = 0; return }
        r.emptySparkles++
        MacroLog.i("no sparkle by (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)})")
    }

    /**
     * A fish stop: walk the path's leg there, find the fish and tap it once (one harpoon throw;
     * hit or miss, the fish is gone for the run, so a stop without one is fine). A fish more than
     * [FISH_REACH] from the party is walked toward first.
     */
    private fun takeFish(r: Run, ms: MapSpec, leg: Route.Leg, at: Point) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${leg.target}")
        walkLeg(r, ms, leg, at)
        fight(r)
        var steps = 0
        var walked = false
        var end = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < end) {
            val p = fishShadow(ctx)
            if (p == null) {
                // not in view: the walk can stop short of the spot (a false "stood still"): walk there by the
                // area map once and look again
                if (System.currentTimeMillis() + 200 >= end && !walked && ms.layout != null) {
                    fishShadow(ctx, explain = true)
                    walked = true
                    MacroLog.i("no fish in view: walking to (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)}) by the area map")
                    val there = searchWalk(r, ms.layout, at, "${leg.target} (none in view)")
                    // the walk ends on the spot by the map's own reads: no second look before the next leg
                    // unless a battle came after it
                    val fought = fight(r)
                    val seg = Route.segOf(ms.layout, at)
                    if (there && !fought && seg != null) {
                        r.pos = Point(at.x.coerceIn(seg.x1, seg.x2), seg.y); r.planPos = r.pos; r.unsure = false; r.lastDir = 0.0
                    } else r.unsure = true
                    end = System.currentTimeMillis() + 3000
                }
                ctx.wait(0.2); continue
            }
            // the throw itself doesn't move the party: only a step toward the fish makes its spot unsure
            if (abs(p.x - 0.5) > FISH_REACH && steps < 4) {
                r.unsure = true
                val dir = if (p.x < 0.5) -1.0 else 1.0
                MacroLog.i("fish at (${"%.2f".format(p.x)}, ${"%.2f".format(p.y)}): a step toward it")
                ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), FISH_STEP_MS); ctx.wait(0.4)
                fight(r)
                steps++
                continue
            }
            MacroLog.i("fish at (${"%.2f".format(p.x)}, ${"%.2f".format(p.y)}): throwing")
            ctx.tapExact(p); ctx.wait(0.8)
            val until = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < until) {
                val read = ctx.readText()
                val all = read.joinToString(" ") { Ocr.norm(it.text) }
                if ("dodged" in all || "harpoon" in all) {
                    MacroLog.i("fish: missed (${read.joinToString(" ") { it.text }.take(80)})")
                    ctx.count("fish_missed")
                    ctx.tap(Point(0.5, 0.65)); ctx.wait(0.5); fieldAfterFish(r)
                    return
                }
                // the catch's card: the fish's name over "Size ... cm"
                if ("size" in all && "cm" in all) {
                    MacroLog.i("fish: caught ${read.joinToString(" ") { it.text }.take(80)}")
                    ctx.count("fish")
                    ctx.tap(Point(0.5, 0.5)); ctx.wait(0.8)
                    // one throw can show more than one card: close every card
                    for (extra in 1..3) {
                        var more = ctx.readText()
                        val look = System.currentTimeMillis() + 1500
                        while (!isFishCard(more) && System.currentTimeMillis() < look) { ctx.wait(0.3); more = ctx.readText() }
                        if (!isFishCard(more)) break
                        MacroLog.i("fish: another card ${more.joinToString(" ") { it.text }.take(80)}")
                        ctx.count("fish")
                        ctx.tap(Point(0.5, 0.5)); ctx.wait(0.8)
                    }
                    fieldAfterFish(r)
                    return
                }
                ctx.wait(0.3)
            }
            MacroLog.i("tapped the fish, nothing came: ${ctx.readText().joinToString(" | ") { it.text }.take(120)}")
            fieldAfterFish(r)
            return
        }
        fishShadow(ctx, explain = true)
        MacroLog.i("no fish by (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)})")
    }

    /**
     * Back to the field after a throw. A catch can be followed by lines of talk (an item levelling
     * up, see the knowledge base): when the field doesn't come back, tap them through, so the next
     * leg doesn't walk blind behind the text box.
     */
    private fun fieldAfterFish(r: Run) {
        if (waitForField(r.ctx, 4)) return
        if (talkThrough(r)) waitForField(r.ctx, 4)
    }

    /** A catch's card on screen: the fish's name over "Size ... cm". */
    private fun isFishCard(read: List<Ocr.Line>): Boolean {
        val all = read.joinToString(" ") { Ocr.norm(it.text) }
        return "size" in all && "cm" in all
    }

    /**
     * A bait jar stop: walk there and tap the jar's bubble (or a "!"); a full jar's catch ("You've
     * caught a total of N fish.", maybe a catch card first) is tapped through to the field. The macro
     * never sets or buys bait: anything that is not a plain collect (a bait list, a purchase) is
     * closed untouched.
     */
    private fun takeJar(r: Run, ms: MapSpec, leg: Route.Leg, at: Point) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${leg.target}")
        walkLeg(r, ms, leg, at)
        fight(r)
        var b: Point? = null
        val end = System.currentTimeMillis() + 3000
        while (b == null && System.currentTimeMillis() < end) {
            ctx.refresh()
            b = (ctx.find(JAR_BUBBLE) ?: ctx.find(BUBBLE))?.center?.takeIf { abs(it.x - 0.5) < 0.3 }
            if (b == null) ctx.wait(0.2)
        }
        if (b == null) { MacroLog.i("${leg.target}: no jar bubble (no bait set, or not full yet)"); return }
        r.unsure = true
        ctx.tapExact(b)
        // the party walks to the jar and pulls it up (about 2 s), then the catch shows
        var caught = false
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) {
            ctx.wait(1.0)
            val read = ctx.readText()
            val all = read.joinToString(" ") { Ocr.norm(it.text) }
            if (all.isNotBlank()) MacroLog.i("${leg.target}: ${read.joinToString(" | ") { it.text }.take(160)}")
            if (buyWords(r.ctx).any { it in all } || "bait" in all) {
                closeMenu(r)
                MacroLog.i("${leg.target}: not full (the game offered bait or a purchase): closed, nothing set or bought")
                return
            }
            // a question to collect first ("Collect ...?" Yes)
            if (listOf("collect", "gather", "retrieve").any { it in all }) {
                val yes = read.firstOrNull { Ocr.norm(it.text) in setOf("yes", "ok") }
                if (yes != null) { ctx.tapExact(yes.center); continue }
            }
            if ("obtain" in all || "obtalned" in all || "caught" in all || ("size" in all && "cm" in all)) {
                if (!caught) { caught = true; ctx.count("jars") }
                noteItems(r, read)
                ctx.tap(Point(0.5, 0.75))
                continue
            }
            if (caught && waitForField(ctx, 1)) break
        }
        if (caught) { MacroLog.i("${leg.target}: emptied"); waitForField(ctx, 4); return }
        MacroLog.i("${leg.target}: nothing to take")
        closeMenu(r)
    }

    /** A collect option of the macro (sparkles, fish, jars): on unless the macro switches it off. */
    private fun collects(ctx: Context, name: String): Boolean =
        ctx.params[name]?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on", "y") } ?: true

    /** Close what a tap opened without choosing anything in it: its Close / Cancel / No / Back, else Android Back. */
    private fun closeMenu(r: Run) {
        val ctx = r.ctx
        repeat(3) {
            if (waitForField(ctx, 1)) return
            val lines = ctx.readText()
            val close = lines.firstOrNull { Ocr.norm(it.text) in setOf("close", "cancel", "no", "back") }
            if (close != null) ctx.tapExact(close.center) else ctx.back()
            ctx.wait(1.0)
            // Back on the field asks "Close the app?": No
            ctx.readText().takeIf { l -> l.any { "close the app" in Ocr.norm(it.text) } }
                ?.firstOrNull { Ocr.norm(it.text) == "no" }?.let { ctx.tapExact(it.center); ctx.wait(1.0) }
        }
    }

    /**
     * A fish over the water: an all-black fish-shaped blob (V <= 24; wider than tall, filling over
     * half its box, area 900-1530 in 1024-wide units) in the upper middle of the screen, with a
     * coloured ring around it (mean saturation 140+, or 70+ for a blob filling 0.53+ of its box). The best by area, or null.
     */
    private fun fishShadow(ctx: Context, explain: Boolean = false): Point? {
        val bgr = ctx.colorMat() ?: return null
        val hsv = org.opencv.core.Mat(); org.opencv.imgproc.Imgproc.cvtColor(bgr, hsv, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV)
        val dark = org.opencv.core.Mat()
        org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(0.0, 0.0, 0.0), org.opencv.core.Scalar(180.0, 255.0, 24.0), dark)
        val w = bgr.cols(); val h = bgr.rows(); val s = w / 1024.0
        val keep = org.opencv.core.Mat.zeros(h, w, org.opencv.core.CvType.CV_8U)
        keep.submat((h * 0.1).toInt(), (h * 0.6).toInt(), (w * 0.03).toInt(), (w * 0.86).toInt()).setTo(org.opencv.core.Scalar(255.0))
        org.opencv.core.Core.bitwise_and(dark, keep, dark)
        val sat = org.opencv.core.Mat(); org.opencv.core.Core.extractChannel(hsv, sat, 1)
        val labels = org.opencv.core.Mat(); val stats = org.opencv.core.Mat(); val cents = org.opencv.core.Mat()
        val n = org.opencv.imgproc.Imgproc.connectedComponentsWithStats(dark, labels, stats, cents)
        var best: Point? = null; var bestArea = 0.0
        // (explain: every dark blob of a fish's rough size, with why it was turned down, for tuning)
        val notes = mutableListOf<String>()
        for (k in 1 until n) {
            val area = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_AREA)[0] / (s * s)
            val bw = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_WIDTH)[0] / s
            val bh = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_HEIGHT)[0] / s
            val cx = cents.get(k, 0)[0]; val cy = cents.get(k, 1)[0]
            fun note(why: String) {
                if (explain && area >= 300 && area <= 30000) notes += "(${"%.2f".format(cx / w)},${"%.2f".format(cy / h)} a${area.toInt()} ${bw.toInt()}x${bh.toInt()} " +
                    "fill ${"%.2f".format(area / (bw * bh))}: $why)"
            }
            if (area < 600 || area > 3000 || bw < 45 || bw > 160 || bh < 15 || bh > 70 || bw / bh < 1.5) { note("size"); continue }
            if (area / (bw * bh) < 0.48) { note("fill"); continue }
            if (cy < h * 0.2 || cy > h * 0.55) { note("height"); continue }
            val m = (14 * s).toInt()
            val bx = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_LEFT)[0].toInt(); val by = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_TOP)[0].toInt()
            val x0 = (bx - m).coerceAtLeast(0); val y0 = (by - m).coerceAtLeast(0)
            val x1 = (bx + (bw * s).toInt() + m).coerceAtMost(w); val y1 = (by + (bh * s).toInt() + m).coerceAtMost(h)
            val ring = org.opencv.core.Mat()
            val labSub = labels.submat(y0, y1, x0, x1); val satSub = sat.submat(y0, y1, x0, x1)
            org.opencv.core.Core.compare(labSub, org.opencv.core.Scalar(k.toDouble()), ring, org.opencv.core.Core.CMP_NE)
            val glow = org.opencv.core.Core.mean(satSub, ring).`val`[0]
            listOf(ring, labSub, satSub).forEach { it.release() }
            // a fish over the blue water has a weaker ring (84-85, 2026-11-24): taken there when its shape is
            // a full one (scenery fills under 0.4)
            if (glow < 140 && (glow < 70 || area / (bw * bh) < 0.53)) { note("glow ${glow.toInt()}"); continue }
            if (area <= bestArea) continue
            bestArea = area; best = Point(cx / w, cy / h)
        }
        if (explain && best == null) MacroLog.i("fish finder: ${if (notes.isEmpty()) "no dark blob of a fish's size" else notes.take(6).joinToString(" ")}")
        listOf(bgr, hsv, dark, keep, sat, labels, stats, cents).forEach { it.release() }
        return best
    }

    /**
     * A sparkle's star: a small near-white green core (H 45-82, S 10-75, V 215+) inside a teal glow (H 74-102,
     * S 85+), on the ground band (y 0.53-0.58). The best core by size x glow, or null.
     */
    private fun sparkleStar(ctx: Context): Point? {
        val bgr = ctx.colorMat() ?: return null
        val hsv = org.opencv.core.Mat(); org.opencv.imgproc.Imgproc.cvtColor(bgr, hsv, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV)
        val core = org.opencv.core.Mat(); val glow = org.opencv.core.Mat()
        org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(45.0, 10.0, 215.0), org.opencv.core.Scalar(82.0, 75.0, 255.0), core)
        org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(74.0, 85.0, 70.0), org.opencv.core.Scalar(102.0, 255.0, 200.0), glow)
        val w = bgr.cols(); val h = bgr.rows(); val s = w / 1024.0
        val labels = org.opencv.core.Mat(); val stats = org.opencv.core.Mat(); val cents = org.opencv.core.Mat()
        val n = org.opencv.imgproc.Imgproc.connectedComponentsWithStats(core, labels, stats, cents)
        var best: Point? = null; var bestScore = 0.0
        for (k in 1 until n) {
            val area = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_AREA)[0] / (s * s)
            val bw = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_WIDTH)[0] / s
            val bh = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_HEIGHT)[0] / s
            if (area < 8 || bw > 16 || bh > 16) continue
            val cx = cents.get(k, 0)[0]; val cy = cents.get(k, 1)[0]
            if (cx < w * 0.08 || cx > w * 0.9 || cy < h * 0.45 || cy > h * 0.68) continue
            val r = (10 * s).toInt().coerceAtLeast(3)
            val x0 = (cx - r).toInt().coerceAtLeast(0); val x1 = (cx + r).toInt().coerceAtMost(w)
            val y0 = (cy - r).toInt().coerceAtLeast(0); val y1 = (cy + r).toInt().coerceAtMost(h)
            val sub = glow.submat(y0, y1, x0, x1)
            val share = org.opencv.core.Core.countNonZero(sub).toDouble() / ((y1 - y0) * (x1 - x0)).coerceAtLeast(1)
            sub.release()
            if (share < 0.5) continue
            val score = area * share
            if (score > bestScore) { bestScore = score; best = Point(cx / w, cy / h) }
        }
        listOf(bgr, hsv, core, glow, labels, stats, cents).forEach { it.release() }
        return best
    }

    /**
     * A sparkle on the field: the biggest pale-green blob on the ground (lower half, y 0.53-0.57
     * band, away from the edges and buttons), or null.
     */
    private fun sparkle(ctx: Context): Point? {
        val bgr = ctx.colorMat() ?: return null
        val hsv = org.opencv.core.Mat(); org.opencv.imgproc.Imgproc.cvtColor(bgr, hsv, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV)
        val mask = org.opencv.core.Mat()
        org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(60.0, 40.0, 140.0), org.opencv.core.Scalar(95.0, 255.0, 255.0), mask)
        val w = bgr.cols(); val h = bgr.rows()
        val keep = org.opencv.core.Mat.zeros(h, w, org.opencv.core.CvType.CV_8U)
        val x0 = (w * 0.08).toInt(); val x1 = (w * 0.9).toInt(); val y0 = (h * 0.4).toInt(); val y1 = (h * 0.8).toInt()
        keep.submat(y0, y1, x0, x1).setTo(org.opencv.core.Scalar(255.0))
        org.opencv.core.Core.bitwise_and(mask, keep, mask)
        val labels = org.opencv.core.Mat(); val stats = org.opencv.core.Mat(); val cents = org.opencv.core.Mat()
        val n = org.opencv.imgproc.Imgproc.connectedComponentsWithStats(mask, labels, stats, cents)
        // ~15 px of a 1024-wide screen at least, and no wider than a sparkle
        val minArea = 15.0 * w * h / (1024.0 * 461.0)
        var best: Point? = null; var bestArea = 0.0
        for (k in 1 until n) {
            val area = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_AREA)[0]
            val bw = stats.get(k, org.opencv.imgproc.Imgproc.CC_STAT_WIDTH)[0]
            if (area < minArea || bw > w * 0.08 || area <= bestArea) continue
            bestArea = area
            best = Point(cents.get(k, 0)[0] / w, cents.get(k, 1)[0] / h)
        }
        listOf(bgr, hsv, mask, keep, labels, stats, cents).forEach { it.release() }
        return best
    }

    /**
     * Follow one leg of the map's path. Each hold aims at the spot the plan puts it at, from
     * where the party really is (a battle can cut a hold short), so the stored ms are what's
     * held when nothing gets in the way. A flick that seems to have done nothing (the screen
     * barely changed) is checked on the area map; when the party isn't where the plan has it,
     * the rest of the leg is walked the old way (looking at the area map).
     */
    private fun walkLeg(r: Run, ms: MapSpec, leg: Route.Leg, goal: Point) = r.ctx.timed("walk") { walkLegInner(r, ms, leg, goal) }

    private fun walkLegInner(r: Run, ms: MapSpec, leg: Route.Leg, goal: Point) {
        val ctx = r.ctx
        val l = ms.layout!!
        val t = table(ctx, ms)
        var p = r.pos!!
        var plan = r.planPos ?: p
        MacroLog.i("path to ${leg.target}: ${leg.steps.joinToString(", ")}")
        fun offPath(why: String) {
            MacroLog.i("$why: walking to ${leg.target} by the area map instead")
            searchWalk(r, l, goal, "${leg.target} ($why)")
            r.pos = Point(goal.x, Route.segOf(l, goal)?.y ?: goal.y)
            r.planPos = r.pos
        }
        // the fixed steps start at the stop before, which was left out: walk there by the area map
        if (r.offPlan) { r.offPlan = false; offPath("the stop before is switched off"); return }
        /**
         * Read where the party is on the area map (once, after a battle or a chest search) and
         * follow from there. False if it isn't on the street the plan has it on.
         */
        fun resync(why: String, target: Double? = null): Boolean {
            val seen = Route.seen(ctx, l, listOf(p, plan), why) { AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
                .minByOrNull { abs(it.x - p.x) + 2 * abs(it.y - p.y) } }
            Route.disturbed = false; r.unsure = false
            if (seen == null) { MacroLog.i("$why: no party marker on the map; keeping (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})"); return true }
            val seg = Route.segOf(l, seen)!!
            MacroLog.i("$why: the map shows (${"%.3f".format(seen.x)}, ${"%.3f".format(seen.y)}), followed (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})")
            var x = seen.x.coerceIn(seg.x1, seg.x2)
            // the party only walks forward: a read a little past the target (a stale or wobbly marker)
            // is taken as being there, so the walk doesn't turn back; only clearly past it (>0.01) turns
            val to = target ?: plan.x
            if (r.lastDir != 0.0 && seg == Route.segOf(l, plan) && (x - to) * r.lastDir > 0 && abs(x - to) <= 0.01) x = to
            p = Point(x, seg.y)
            return seg == Route.segOf(l, plan)
        }
        /** Hold toward [x] on this street (into the wall when [x] is the street's end). */
        fun walkTo(x: Double) {
            val s = Route.segOf(l, p)
            var d = x - p.x
            if (s != null && (abs(x - s.x1) < 1e-6 || abs(x - s.x2) < 1e-6) && abs(d) >= t.minWalk) d += if (d > 0) t.wallMargin else -t.wallMargin
            if (abs(d) < t.minWalk) return
            r.lastDir = if (d > 0) 1.0 else -1.0
            p = Route.hold(ctx, l, t, p, d > 0, t.holdMs(abs(d)).toLong()) { fight(r) }
            if (MiniMap.compare) try {
                val m = MiniMap.locate(ctx, l, listOf(p))
                MacroLog.i("minimap after the hold: ${m?.let { "x ${"%.3f".format(it.at.x)} (followed ${"%.3f".format(p.x)}, off by ${"%.3f".format(it.at.x - p.x)})" } ?: "no clear read"}")
            } catch (e: Exception) { MacroLog.i("minimap: ${e.message}") }
            // a battle the hold didn't see (it came anyway): where the party stopped is unknown, read it and walk the rest
            repeat(6) {
                if (!Route.unseen) return
                Route.unseen = false
                if (!resync("a battle came during the hold", x)) return
                val rest = x - p.x
                if (abs(rest) < t.minWalk) return
                p = Route.hold(ctx, l, t, p, rest > 0, t.holdMs(abs(rest) + if (s != null && (abs(x - s.x1) < 1e-6 || abs(x - s.x2) < 1e-6)) t.wallMargin else 0.0).toLong()) { fight(r) }
            }
        }
        Route.disturbed = false
        // a leg to a sparkle with no stairs on the way needs no exact spot: the sparkle is found on screen,
        // and its pickup puts the party on its spot. The unsure spot is read later only if the sparkle
        // doesn't show (saves an area-map look)
        val roughOk = leg.sparkle != null && leg.steps.none { it is Route.Step.Flick }
        if (r.unsure && roughOk) MacroLog.i("path to ${leg.target}: no stairs, no map look for the spot")
        if (r.unsure && !roughOk && !resync("before the leg (the spot is unsure)")) { r.pos = p; offPath("not on the street the path expects"); return }
        for (st in leg.steps) {
            if (Route.segOf(l, p) != Route.segOf(l, plan)) { r.pos = p; offPath("not on the street the path expects"); return }
            when (st) {
                is Route.Step.Hold -> {
                    plan = Route.walked(l, plan, st.right, t.distance(st.ms.toDouble()))
                    walkTo(plan.x)
                }
                is Route.Step.Flick -> {
                    // a battle cut a walk short: read the real spot once, stairs need it
                    if (Route.disturbed && !resync("before the stairs after a battle")) { r.pos = p; offPath("not on the street the path expects"); return }
                    // no battle: still check the spot on the minimap (no area map, it's cheap); stairs need ±0.006
                    if (!Route.disturbed) try {
                        MiniMap.locate(ctx, l, listOf(p))?.let { m ->
                            if (Route.segOf(l, m.at) == Route.segOf(l, plan) && abs(m.at.x - p.x) >= 0.003) {
                                MacroLog.i("before the stairs: the minimap shows x ${"%.3f".format(m.at.x)}, followed ${"%.3f".format(p.x)}")
                                p = m.at
                            }
                        }
                    } catch (e: Exception) { MacroLog.i("minimap: ${e.message}") }
                    val next = Route.climbed(l, plan, st.up) ?: run { r.pos = p; offPath("the path has no stairs ${if (st.up) "up" else "down"} here"); return }
                    var took = false
                    // the stairs can be a little off in the data: from the 2nd try, flick a little either side
                    val offsets = listOf(0.0, -0.006, 0.006, -0.012, 0.012)
                    for (attempt in 1..offsets.size) {
                        val stairsPart = ctx.timings.begin(if (attempt == 1) "stairs" else "stairs again")
                        try {
                            // stairs only take the party standing on them
                            val at = plan.x + offsets[attempt - 1]
                            if (abs(p.x - at) >= t.minWalk) walkTo(at)
                            // a battle on the walk to the stairs: read where it left the party and walk on to them
                            // before flicking (a flick from the wrong spot is a wasted try). A hold's last moment often
                            // starts one: stand still a moment, fight it first, then flick from the real spot
                            for (again in 1..4) {
                                ctx.wait(0.2) // standing still on the stairs before the flick (the hold already stood 0.4 s)
                                if (fight(r)) Route.disturbed = true
                                if (!Route.disturbed) break
                                if (!resync("on the way to the stairs after a battle", at)) { r.pos = p; offPath("not on the street the path expects"); return }
                                if (abs(p.x - at) >= t.minWalk) walkTo(at)
                            }
                            val flickAt = System.currentTimeMillis()
                            val before = ctx.refresh().gray.clone()
                            AreaNav.flickSettled(ctx, st.up)
                            val settledMs = System.currentTimeMillis() - flickAt
                            val after = ctx.refresh().gray.clone()
                            val change = Route.change(before, after)
                            before.release(); after.release()
                            val battle = fight(r)
                            MacroLog.i("flick ${st} (try $attempt) at ${Battle.clock(flickAt)}: settled in ${settledMs}ms, screen change ${"%.1f".format(change)}${if (battle) " (a battle came)" else ""}")
                            if (!battle && change >= t.flickChange) {
                                took = true
                                if (attempt > 1) MacroLog.i("stairs ${st} taken at x≈${"%.3f".format(p.x)} (data ${"%.3f".format(plan.x)}): fix the link in the dungeon data")
                                break
                            }
                            // check on the map: on the far end of the stairs, or still at their foot?
                            val seen = Route.seen(ctx, l, listOf(next, Point(p.x, plan.y)), "after the flick ${st}") { AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
                                .minByOrNull { minOf(abs(it.x - next.x) + 2 * abs(it.y - next.y), abs(it.x - plan.x) + 2 * abs(it.y - plan.y)) } }
                            Route.disturbed = false
                            if (seen != null && abs(seen.y - next.y) < AreaNav.ROW_TOL) {
                                took = true
                                if (attempt > 1) MacroLog.i("stairs ${st} taken at x≈${"%.3f".format(p.x)} (data ${"%.3f".format(plan.x)}): fix the link in the dungeon data")
                                break
                            }
                            if (seen != null && Route.segOf(l, seen) == Route.segOf(l, plan)) {
                                // still at the stairs (a battle came first, or the flick missed them): flick again from here
                                MacroLog.i("still at the stairs (map shows x=${"%.3f".format(seen.x)}, stairs ${"%.3f".format(plan.x)}): again, a little to the side")
                                p = Point(seen.x, plan.y)
                                Route.unseen = false
                                continue
                            }
                            r.pos = seen?.let { Point(it.x, Route.segOf(l, it)!!.y) } ?: p
                            offPath("the flick ${st} didn't take the stairs (map shows ${seen ?: "no marker"})")
                            return
                        } finally { ctx.timings.end(stairsPart) }
                    }
                    if (!took) { r.pos = p; offPath("the flick ${st} didn't take the stairs in ${offsets.size} tries"); return }
                    plan = next
                    // the party comes off the stairs where it went on (the data can be a little off)
                    p = Point(p.x, next.y)
                }
            }
            r.pos = p
        }
        // a battle on the last walk of the leg: read the real spot and finish the walk. Not for a
        // chest: its "!" shows when the followed spot is right (the usual case), and takeChest
        // reads the map only when it doesn't (saves a look at the area map, ~3 s, per battle)
        for (again in 1..4) {
            if (!Route.disturbed || leg.chest != null) break
            if (Route.readAtEnd) {
                // the last hold stood still and read the spot on the map after the battle: no second look
                Route.disturbed = false; r.unsure = false
                if (Route.segOf(l, p) != Route.segOf(l, plan) || abs(p.x - plan.x) < t.minWalk) break
                walkTo(plan.x)
                continue
            }
            if (!resync("at the end of the leg after a battle") || abs(p.x - plan.x) < t.minWalk) break
            walkTo(plan.x)
        }
        r.pos = p
        r.planPos = plan
    }

    /**
     * Watch for the "!" for up to [seconds] and open it. Logs the best bubble score seen when it
     * doesn't show (how close the template came).
     */
    private fun waitBubble(r: Run, seconds: Double): Boolean {
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        var best = 0.0
        var frames = 0
        while (System.currentTimeMillis() < end) {
            if (openBubble(r)) return true
            frames++
            best = maxOf(best, r.ctx.vision.library.templates[BUBBLE]?.lastScore ?: 0.0)
            Thread.sleep(200)
        }
        MacroLog.i("no \"!\" in ${seconds}s ($frames frames, best bubble score ${"%.2f".format(best)})")
        return false
    }

    /** Tap a "!" bubble on screen and clear what it opens (a chest's "Obtained ..."). True if there was one. */
    private fun openBubble(r: Run, small: Boolean = false): Boolean {
        val ctx = r.ctx
        ctx.refresh()
        val b = ctx.find(BUBBLE) ?: ctx.find(BUBBLE_CORE) ?: (if (small) ctx.find(BUBBLE_SMALL) else null) ?: return false
        val tapAt = System.currentTimeMillis()
        ctx.tap(b); ctx.wait(0.5)
        // the chest's banner comes ~1-2 s after the tap: read just the top of the screen (quicker) every
        // 0.2 s until it shows, for up to ~3.5 s; anything else (a question, a door) gets a full read
        var read = emptyList<Ocr.Line>()
        var found = false
        var last = ""
        val until = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < until) {
            read = ctx.readText(POPUP)
            val all = read.joinToString(" ") { Ocr.norm(it.text) }
            if ("obtained" in all || "proceed" in all || "major trial" in all) { found = true; break }
            // the same other text twice (e.g. a place's name over a door): it's not a chest's banner
            if (all.isNotBlank() && all == last) break
            last = all
            ctx.refresh()
            if (Battle.inBattle(ctx)) break
            ctx.wait(0.2)
        }
        if (!found) read = ctx.readText()
        val text = read.joinToString(" ") { it.text }
        MacroLog.i("\"!\": ${text.take(120)} (tapped ${Battle.clock(tapAt)}, read +${System.currentTimeMillis() - tapAt}ms)")
        noteItems(r, read)
        // a chest found while looking around a map without a layout
        if (Ocr.norm(text).contains("obtained") && r.spec.maps.getOrNull(r.map)?.layout == null) ctx.count("chests")
        if (bossGate(r)) { fight(r); return true }
        // a chest's "Obtained ...": close it and go on as soon as the field is back (a battle check
        // on the banner itself waits ~5 s for a battle that isn't coming)
        if ("obtained" in Ocr.norm(text)) {
            ctx.tap(Point(0.5, 0.75)); ctx.wait(0.5)
            if (!waitForField(ctx, 6)) fight(r)
            MacroLog.i("chest popup closed, field back +${System.currentTimeMillis() - tapAt}ms")
            return true
        }
        if (fight(r)) return true // the boss (or a guard) behind it
        ctx.tap(Point(0.5, 0.75)); ctx.wait(1.2)
        waitForField(ctx, 6)
        return true
    }

    /**
     * Fixed steps to [goal] from the party's followed spot, for a map with no path of its own (its
     * stops are read on arrival): null when the spot isn't known, so the walk goes by the area map.
     */
    /**
     * The open portal's trip ([MapSpec.portal]): walk to its spot, tap the door sign, on its map
     * tap the "!" and tap through, then the door sign back. The report names the outcome.
     */
    private fun takePortal(r: Run, ms: MapSpec, p: Portal) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${p.what} (portal open)")
        val leg = plannedLeg(r, ms, p.at, "portal")
        if (leg != null) walkLeg(r, ms, leg, p.at) else searchWalk(r, ms.layout!!, p.at, "${p.what} portal (party not seen on the map)")
        fight(r)
        fun sign(seconds: Double): Match? {
            val until = System.currentTimeMillis() + (seconds * 1000).toLong()
            while (System.currentTimeMillis() < until) {
                ctx.refresh()
                ARROWS.mapNotNull { ctx.find(it) }.minByOrNull { abs(it.center.x - 0.5) }?.let { return it }
                ctx.wait(0.25)
            }
            return null
        }
        // the sign shows once the party stands at the portal: up onto it if it isn't there yet
        var door = sign(2.5)
        if (door == null) { AreaNav.flick(ctx, up = true); ctx.wait(1.0); fight(r); door = sign(2.0) }
        if (door == null) {
            r.trips += "${p.what}: portal open but its sign wasn't found"
            MacroLog.i("${p.what}: no door sign at the portal"); r.unsure = true; return
        }
        MacroLog.i("${p.what}: taking the portal at (${"%.2f".format(door.center.x)}, ${"%.2f".format(door.center.y)})")
        ctx.tap(door); ctx.wait(2.0)
        answerExitQuestion(r)
        if (!waitForField(ctx, 15)) { fight(r); waitForField(ctx, 10) }
        ctx.wait(1.0)
        // the "!" on the trip's map, right by the arrival
        var bubble: Match? = null
        val until = System.currentTimeMillis() + 4000
        while (bubble == null && System.currentTimeMillis() < until) { ctx.refresh(); bubble = ctx.find(BUBBLE) ?: ctx.find(BUBBLE_CORE); if (bubble == null) ctx.wait(0.3) }
        if (bubble != null) {
            ctx.tap(bubble); ctx.wait(2.0)
            talkThrough(r)
            waitForField(ctx, 10)
            r.trips += "${p.what}: taken"
            ctx.count("portal_trips")
            MacroLog.i("${p.what}: taken")
        } else {
            r.trips += "${p.what}: portal taken but no \"!\" on its map"
            MacroLog.i("${p.what}: no \"!\" on the portal's map")
        }
        // back: the door sign by the party
        ctx.wait(1.0)
        val back = sign(4.0)
        if (back == null) { r.trips += "${p.what}: no way back found"; MacroLog.i("${p.what}: no door sign back"); r.moved = true; return }
        ctx.tap(back); ctx.wait(2.0)
        answerExitQuestion(r)
        waitForField(ctx, 15); ctx.wait(1.0)
        // back at the portal: the walk goes on from its spot
        r.pos = p.at; r.planPos = p.at; r.unsure = false
    }

    private fun plannedLeg(r: Run, ms: MapSpec, goal: Point, target: String): Route.Leg? {
        val l = ms.layout ?: return null
        val from = r.pos ?: return null
        return Route.plan(l, table(r.ctx, ms), from, goal, target)
    }

    /** Walk to the map's exit and take its ground arrow (tap it); flick as a fallback. */
    private fun leave(r: Run, ms: MapSpec, idx: Int) {
        val ctx = r.ctx
        r.moved = true
        task(r, "Map ${idx + 1}: to the stairs down")
        val exit = ms.exit!!
        MacroLog.i("to the exit at (${"%.3f".format(exit.x)}, ${"%.3f".format(exit.y)})")
        val leg = ms.path?.firstOrNull { it.target == "exit" } ?: plannedLeg(r, ms, exit, "exit")
        if (leg != null && r.pos != null) walkLeg(r, ms, leg, exit) else searchWalk(r, ms.layout!!, exit, "exit (no fixed path)")
        if (ms.exitWalk) {
            // off the screen's edge: hold on that way until the next map shows
            val dir = if (exit.x > 0.5) 1.0 else -1.0
            r.expect = idx + 1
            ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), 3000); ctx.wait(1.5)
            fight(r)
            return
        }
        // the arrow shows on the ground once the party is near; walk on toward the exit until it does
        val toward = if (exit.x > 0.5) 1.0 else -1.0
        for (attempt in 0..7) {
            // the sign can show only a moment after the party stops: watch for it a little first
            var arrow: Match? = null
            val until = System.currentTimeMillis() + if (attempt == 0) 2500 else 800
            while (arrow == null && System.currentTimeMillis() < until) {
                ctx.refresh()
                arrow = (ms.exitSign?.let { listOf(it) } ?: ARROWS).mapNotNull { ctx.find(it) }.minByOrNull { abs(it.center.x - 0.5) }
                if (arrow == null) ctx.wait(0.25)
            }
            if (arrow != null) {
                MacroLog.i("taking the ground arrow at (${"%.2f".format(arrow.center.x)}, ${"%.2f".format(arrow.center.y)})")
                ctx.tap(arrow); ctx.wait(1.5)
                r.expect = idx + 1
                // a travel question at the exit ("Go to <map>? ..."): Yes
                answerExitQuestion(r)
                // the next map's title shows over the field as it opens: read it there (no area-map look)
                val titled = arrivalTitle(r, idx, 6.0)
                if (titled != null) {
                    MacroLog.i("arrived (title): ${r.spec.maps[titled].names.first()}")
                    // the title shows while the map still fades in: a hold then walks only part of its way
                    waitForField(ctx, 8); ctx.wait(0.5)
                    r.nextMap = titled; return
                }
                ctx.wait(1.0)
                fight(r)
                val now = whereAmI(r)
                // (the next map is known now: no second look at the area map for it)
                if (now != idx) { if (now != null) r.nextMap = now; return }
            }
            // the sign didn't show (or didn't take the party on) where the path ends: looking for it nearby
            if (attempt == 0) r.searched += "map ${idx + 1}: looked for the exit's sign around (${"%.3f".format(exit.x)}, ${"%.3f".format(exit.y)})"
            when (attempt) {
                3 -> AreaNav.flick(ctx, up = false)
                5 -> AreaNav.flick(ctx, up = true)
                else -> AreaNav.hold(ctx, if (attempt < 3) -toward else toward, 0.008)
            }
            fight(r)
        }
        MacroLog.i("the exit didn't take us anywhere yet")
    }

    /** A fish further than this from the screen's middle (where the party stands) is walked toward before the throw. */
    private const val FISH_REACH = 0.3
    private const val FISH_STEP_MS = 400L
    /** A sparkle this close to the screen's middle (where the party stands) is stepped away from before a retap. */
    private const val SPARKLE_NEAR = 0.06
    /** That step: a short walk. */
    private const val SPARKLE_STEP_MS = 350L
    /** Empty sparkle stops in a row (none picked up yet in the run) after which a stop gets a glance only. */
    private const val GLANCE_AFTER = 2

    /**
     * A Yes/No question right after taking an exit ("Go to <map>? ..."): Yes. Never when the box
     * asks for anything bought.
     */
    private fun answerExitQuestion(r: Run) {
        val ctx = r.ctx
        val until = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < until) {
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            val yes = lines.firstOrNull { Ocr.norm(it.text) == "yes" }
            if (yes != null && lines.any { Ocr.norm(it.text) == "no" }) {
                if (buyWords(r.ctx).any { it in all }) {
                    tapText(r, "No", exact = true, tries = 1)
                    ctx.stop("the way on asks for something bought: '${lines.joinToString(" ") { it.text }.take(120)}'")
                }
                MacroLog.i("question at the exit: '${lines.joinToString(" ") { it.text }.take(120)}' → Yes")
                ctx.tapExact(yes.center); ctx.wait(1.5)
                return
            }
            ctx.wait(0.3)
        }
    }

    /**
     * Lines of talk on screen (no field, no battle): tap through them, up to [max] taps. Stops at
     * a Yes/No question.
     */
    private fun talkThrough(r: Run, max: Int = 40): Boolean {
        val ctx = r.ctx
        var tapped = 0
        while (tapped < max) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) return tapped > 0
            if (Battle.inBattle(ctx)) return tapped > 0
            val lines = ctx.readText()
            if (lines.isEmpty() || lines.any { val t = Ocr.norm(it.text); t == "yes" || t == "no" }) return tapped > 0
            if (buyWords(r.ctx).any { w -> lines.any { w in Ocr.norm(it.text) } }) return tapped > 0
            if (tapped == 0) MacroLog.i("talk: ${lines.joinToString(" ") { it.text }.take(100)}")
            ctx.tap(Point(0.5, 0.85)); ctx.wait(0.8)
            tapped++
        }
        return true
    }

    /** Fights on one map before it is taken as cleared even without the banner (a safety cap). */
    internal const val CLEAR_MAX_BATTLES = 15

    /** The round signs where a staircase/door leads to another map (on the ground, or a door sign over it). */
    private val ARROWS = listOf("ground_arrow_bars", "ground_arrow_steps", "door_sign", "door_sign_round", "ladder_sign")

    /**
     * Walk back and forth fighting (like grind-battles with until_cleared) until the game says
     * "The enemies have disappeared": the enemies the walk to every stop didn't meet, with the
     * `defeat_all` option on, on a map whose enemies run out. Safety caps for a missed
     * banner: [clear_max_battles] fights won on this map (15 by default, 0 = no cap), or no
     * encounter in 12 walks.
     */
    private fun grind(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        task(r, "${ms.names.first()}: fighting the enemies left (back and forth)")
        val cap = ctx.params["clear_max_battles"]?.trim()?.toIntOrNull() ?: CLEAR_MAX_BATTLES
        var won = 0
        var dir = 1.0
        var quiet = 0
        // on a path: back and forth between two spots of this street, so the position stays known
        val l = ms.layout
        val seg = r.pos?.let { p -> l?.let { Route.segOf(it, p) } }
        // (longer one-way walks start encounters sooner than many short turns)
        val span = r.pos?.let { p -> seg?.let { s -> if (s.x2 - p.x >= p.x - s.x1) p.x to minOf(s.x2, p.x + GRIND_SPAN) else maxOf(s.x1, p.x - GRIND_SPAN) to p.x } }
        // "The enemies have disappeared" shows for a moment, often while the party walks or the game
        // autosaves: watch the whole screen for it all along, beside the walking and fighting
        // (the spot is read on the map once after clearing: no map looks for it while walking back and forth)
        Route.loose = true
        try { BannerWatch(r).use { watch ->
            while (quiet < 12) {
                if (watch.check()) return
                val p = r.pos
                var fought = false
                if (l != null && p != null && span != null) {
                    val t = table(ctx, ms)
                    val to = if (abs(p.x - span.first) < abs(p.x - span.second)) span.second else span.first
                    if (abs(to - p.x) >= t.minWalk) r.pos = Route.hold(ctx, l, t, p, to > p.x, t.holdMs(abs(to - p.x)).toLong()) { fight(r).also { if (it) fought = true } }
                } else AreaNav.hold(ctx, dir, 0.05)
                dir = -dir
                if (fight(r) || fought) { quiet = 0; won++ } else quiet++
                if (watch.check() || r.cleared) return
                if (cap > 0 && won >= cap) {
                    MacroLog.i("$won fights on ${ms.names.first()} and no \"enemies have disappeared\" seen: taking it as cleared (safety cap)")
                    r.cleared = true
                    return
                }
            }
            MacroLog.i("no encounter in $quiet walks: taking ${ms.names.first()} as cleared")
            r.cleared = true
        } } finally { Route.loose = false }
    }

    /** Reads the whole screen over and over on its own thread while a map is being cleared, for the banner. */
    private class BannerWatch(private val r: Run) : AutoCloseable {
        @Volatile private var seen = false
        @Volatile private var done = false
        private val worker = Thread {
            while (!done && !seen) {
                try { if (noEnemiesLeft(r.ctx.readText().joinToString(" ") { Ocr.norm(it.text) })) seen = true }
                catch (_: Throwable) { }
                if (!seen) try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "banner-watch"; start() }

        init { r.watching = true }

        /** True (and the map marked cleared) once the banner was seen. */
        fun check(): Boolean {
            if (seen && !r.cleared) { MacroLog.i("the enemies have disappeared"); r.cleared = true; r.ctx.count("maps_cleared") }
            return r.cleared
        }

        override fun close() { r.watching = false; done = true; worker.interrupt(); runCatching { worker.join(2000) } }
    }

    /** "The enemies have disappeared" in normalized OCR text (also when OCR splits or clips the line). */
    /**
     * After every stop of a map whose enemies run out, fight the ones left until "The enemies have
     * disappeared" (`defeat_all`; options saved before it was renamed still say `clear_first`).
     * Off, or a dungeon without the option: straight to the exit.
     */
    internal fun defeatAll(params: Map<String, String>) = (params["defeat_all"] ?: params["clear_first"])?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on", "y") } ?: false
    private fun defeatAll(ctx: Context) = defeatAll(ctx.params)

    internal fun noEnemiesLeft(text: String) = "disappear" in text

    /** Where "The enemies have disappeared" shows: the top middle (kept wide: its exact spot is not measured). */
    private val BANNER = Region(0.05, 0.0, 0.9, 0.45)
    /** Where a chest's "Obtained ..." banner shows (top middle). */
    private val POPUP = Region(0.15, 0.0, 0.7, 0.25)

    /** One read of the screen after a win: the map's enemies gone, the White Key. */
    private fun readAfterWin(r: Run, quick: Boolean = false): String {
        // the banner sits at the top middle: reading only there is quicker (it shows for a moment)
        val lines = if (quick) r.ctx.readText(BANNER) else r.ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if (!quick) { noteRewards(r, lines); endNotSkip(r, lines) }
        if (!r.cleared && noEnemiesLeft(all)) { MacroLog.i("the enemies have disappeared"); r.cleared = true; r.ctx.count("maps_cleared") }
        if ("white key" in all || "phantom crystal" in all) {
            if (!r.phantom) MacroLog.i("White Key: the Phantom Crystal Dimension is open")
            r.phantom = true
        }
        return all
    }

    /**
     * A map without a known layout (the boss's): walk to one end, then to the other in
     * short steps, tapping every "!" (chests, the boss); then try the streets above/below.
     */
    private fun sweep(r: Run) {
        val ctx = r.ctx
        if (!r.cleared && !r.onBossMap && defeatAll(ctx)) { grind(r, r.spec.maps[r.map]); return }
        task(r, "Map ${r.map + 1}: looking around (chest, boss)")
        if (r.searched.none { it.startsWith("map ${r.map + 1}: looked around") })
            r.searched += "map ${r.map + 1}: looked around (no layout${if (r.onBossMap) ", boss map" else ""})"
        val ms = r.spec.maps[r.map]
        val hasChests = ms.chests.isNotEmpty() || ms.chestsCount > 0
        for (vertical in listOf(null, true, false)) {
            if (vertical != null) { AreaNav.flick(ctx, vertical); fight(r); if (r.bossBeaten) return }
            for (dir in listOf(-1.0, 1.0)) {
                var still = 0
                var last: Point? = null
                for (step in 0 until 30) {
                    if (bossGate(r)) { fight(r); if (r.bossBeaten) return }
                    // a map without chests: its "!" only leads back, leave it be
                    if (hasChests && openBubble(r)) {
                        ctx.count("bubbles")
                        if (r.bossBeaten) return
                        // a chest, or a portal to the next map: look again
                        r.moved = true
                        return
                    }
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

    /**
     * A small map by its fixed steps (the dungeon data's `steps:`). Then the map is read again: if the
     * party is still here the steps run again (a chest already open shows no "!", so it costs only
     * a look).
     */
    private fun runSteps(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${ms.names.first()}")
        for (step in ms.steps!!) {
            // the boss came up during an earlier step: the run is over, no more steps
            if (r.bossBeaten) return
            val parts = step.trim().split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "BUBBLE" -> {
                    // a chest's "!" can show a few seconds after arriving: watch for up to 8 s
                    var tapped = false
                    val until = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < until) { if (openBubble(r)) { tapped = true; break }; ctx.wait(0.4) }
                    if (!tapped) MacroLog.i("step bubble: no \"!\" here")
                }
                // "boss L": walk that way in plain holds until the boss's question shows, then fight it
                "BOSS" -> {
                    val dir = if (parts.getOrNull(1)?.uppercase() == "R") 1.0 else -1.0
                    for (i in 0 until 20) {
                        if (bossGate(r)) { fight(r); break }
                        if (fight(r) && r.bossBeaten) break
                        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), 1500)
                        ctx.wait(0.5)
                    }
                }
                // "chest L": walk that way in short steps until the chest's "!" shows (drawn small on a
                // zoomed-out map) and open it. The boss's question first means the chest was passed: No, and
                // back the other way
                "CHEST" -> {
                    var dir = if (parts.getOrNull(1)?.uppercase() == "R") 1.0 else -1.0
                    var turned = false
                    // "chest L 2000": one walk of that many ms first, short of the chest, then the short steps
                    parts.getOrNull(2)?.toLongOrNull()?.let { ms0 ->
                        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), ms0); ctx.wait(0.6)
                    }
                    for (i in 0 until 24) {
                        if (openBubble(r, small = true)) break
                        val lines = ctx.readText()
                        if (lines.any { val t = Ocr.norm(it.text); "major trial" in t || "proceed" in t }) {
                            lines.firstOrNull { Ocr.norm(it.text) == "no" }?.let { ctx.tapExact(it.center); ctx.wait(1.5) }
                            if (turned) break
                            MacroLog.i("step chest: at the boss's question, no \"!\" on the way: back the other way")
                            r.searched += "map ${r.map + 1}: step '$step' passed the chest: looked for it on the way back"
                            dir = -dir; turned = true
                            continue
                        }
                        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), 400)
                        ctx.wait(0.6)
                    }
                }
                // "gate": a "!" that asks to travel on and uses up an item ("Go to <map>? ... *Uses 1 <item>."
                // Yes). Without the item ("Not enough <item>" | Cancel) the box is closed with Back (never a
                // tap there) and the run stops
                "GATE" -> {
                    var b: Match? = null
                    val until = System.currentTimeMillis() + 6000
                    while (b == null && System.currentTimeMillis() < until) { ctx.refresh(); b = ctx.find(BUBBLE) ?: ctx.find(BUBBLE_CORE); if (b == null) ctx.wait(0.4) }
                    if (b == null) { MacroLog.i("step gate: no \"!\" here"); continue }
                    ctx.tap(b); ctx.wait(1.2)
                    val text = ctx.readText().joinToString(" ") { it.text }
                    MacroLog.i("gate: ${text.take(120)}")
                    if ("not enough" in Ocr.norm(text)) {
                        // Back does not close this box and the purchase guard refuses a tap on its Cancel:
                        // the user closes it
                        ctx.stop("the way on needs an item you don't have: '${text.take(100)}'. Tap Cancel on the box, " +
                            "then buy the item yourself (the macro never spends Sea-Drifts)")
                    }
                    answerExitQuestion(r)
                    r.expect = r.map + 1; r.moved = true
                    return
                }
                // "yes": the travel question a flick just opened ("Go to <map>? ... No / Yes"): Yes, and the
                // next map follows
                "YES" -> {
                    answerExitQuestion(r)
                    r.expect = r.map + 1; r.moved = true
                    return
                }
                "U" -> AreaNav.flick(ctx, up = true)
                "D" -> AreaNav.flick(ctx, up = false)
                "L", "R" -> {
                    val ms2 = parts.getOrNull(1)?.toLongOrNull() ?: 500
                    ctx.drag(Point(0.5, 0.65), Point(if (parts[0].uppercase() == "L") 0.4 else 0.6, 0.65), ms2)
                    ctx.wait(0.5)
                }
                else -> MacroLog.i("unknown step '$step'")
            }
            fight(r)
            if (r.bossBeaten) return
        }
        // the next map may share this one's title: the next one is expected
        r.expect = r.map + 1
        r.moved = true
    }

    /** Fight a battle if one is on screen: boss setup for the boss. True if there was one. */
    private fun fight(r: Run): Boolean {
        val ctx = r.ctx
        var wasBoss = false
        val t0 = System.currentTimeMillis()
        var resultsMs = 0L
        // the run's end follows the boss battle's results: read every screen for the White Key
        val out = Battle.fight(ctx, afterWin = {
            val r0 = System.currentTimeMillis()
            val seen = readAfterWin(r)
            resultsMs += System.currentTimeMillis() - r0
            // (for tuning how long the boss's result screens take)
            if (wasBoss) MacroLog.i("boss results ${Battle.clock()}: ${seen.take(70)}")
        }) { c ->
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
                if (wasBoss) { r.bossBeaten = true; ctx.count("bosses"); MacroLog.i("the boss is beaten") }
                // "The enemies have disappeared" shows for a moment: a short look here (while a map is
                // cleared, BannerWatch keeps looking as the party walks on)
                val end = System.currentTimeMillis() + 1000
                val seen = LinkedHashSet<String>()
                while (!r.watching && !r.cleared && !r.bossBeaten && System.currentTimeMillis() < end) readAfterWin(r, quick = true).takeIf { it.isNotBlank() }?.let { seen += it }
                // for tuning the banner's spot and timing: what the top of the screen said meanwhile
                if (!r.cleared && !r.onBossMap && seen.isNotEmpty()) MacroLog.i("top after the win: ${seen.joinToString(" | ").take(160)}")
                if (r.bossBeaten) checkPhantom(r)
                // where the time goes: the fight, and its result screens apart
                val kind = if (wasBoss) "boss battle" else "battle"
                ctx.timings.add(kind, System.currentTimeMillis() - t0 - resultsMs)
                if (resultsMs > 0) ctx.timings.add("$kind results", resultsMs)
            }
        }
        return true
    }

    /** "A major trial awaits you beyond this point. Proceed?": the boss. Yes, and the next battle is the boss. */
    private fun bossGate(r: Run): Boolean {
        val lines = r.ctx.readText()
        val all = lines.joinToString(" ") { Ocr.norm(it.text) }
        if ("major trial" !in all && "proceed" !in all) return false
        // "Moving to <place>. Do you wish to proceed?" is a trip on the world map, not the boss
        if ("moving to" in all) return false
        val yes = lines.firstOrNull { Ocr.norm(it.text) == "yes" } ?: return false
        task(r, "The boss: proceeding")
        r.ctx.tapExact(yes.center)
        r.bossNext = true
        r.ctx.wait(3.0)
        return true
    }

    // --- 6. the end ------------------------------------------------------------------
    /** Tap through the completion screens, noting a White Key / Phantom Crystal Dimension. */
    private fun finish(r: Run): String = r.ctx.timed("after the boss") { finishInner(r) }

    private fun finishInner(r: Run): String {
        val ctx = r.ctx
        var rewardWaits = 0
        repeat(25) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) {
                if (whereAmI(r) != null) return "${r.spec.name}: the boss is beaten but the party is still inside"
                return if (r.phantom) phantomDone(r) else "${r.spec.name} done"
            }
            if (Battle.inBattle(ctx)) { fight(r); return@repeat }
            val lines = ctx.readText()
            // (for tuning how long the screens after the boss take)
            MacroLog.i("after the boss ${Battle.clock()}: ${lines.joinToString(" | ") { it.text }.take(70)}")
            readPhantom(r, lines)
            // after End the game is back on the Another Dungeon map: its "???" tab glows with a White Key
            if (lines.any { Ocr.norm(it.text) == "another dungeon" } && lines.any { Ocr.norm(it.text) == "hollow" }) {
                if (phantomTabGlows(ctx)) { if (!r.phantom) MacroLog.i("the ??? tab glows: White Key"); r.phantom = true }
                ctx.tap(AD_CLOSE); ctx.wait(2.0)
                return@repeat
            }
            if (rewardScreen(lines) && noteRewards(r, lines) == 0) {
                // the rows fade in after the title: give them a moment before tapping past the screen
                if (rewardWaits < 2) { rewardWaits++; ctx.wait(1.5); return@repeat }
                MacroLog.i("no rewards read on: ${lines.joinToString(" | ") { it.text }.take(400)}")
            }
            rewardWaits = 0
            // "Continue to skip?  End / Skip": End (Skip would spend another key and ticket)
            if (endNotSkip(r, lines)) return@repeat
            // "Travel to <place>? No / Yes" after the run: No, never travel away from where the run ended
            if (lines.any { Ocr.norm(it.text).startsWith("travel to") }) {
                lines.firstOrNull { Ocr.norm(it.text) == "no" }?.let {
                    MacroLog.i("\"${lines.first { l -> Ocr.norm(l.text).startsWith("travel to") }.text}\": No")
                    ctx.tapExact(it.center); ctx.wait(1.5)
                    return@repeat
                }
            }
            tapText(r, "OK", "Close", "Next", exact = true, tries = 1)
                ?: ctx.tap(Point(0.5, 0.2)) // the rest closes with a tap anywhere: up here, away from buttons
            // (a longer wait made the screens after the boss take much longer; each read takes a moment itself)
            ctx.wait(1.0)
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

    /** On the Another Dungeon map: the "???" tab glows cyan while a White Key is held. */
    private fun phantomTabGlows(ctx: Context): Boolean {
        val bgr = ctx.colorMat() ?: return false
        val roi = bgr.submat((bgr.rows() * 0.037).toInt(), (bgr.rows() * 0.185).toInt(), (bgr.cols() * 0.342).toInt(), (bgr.cols() * 0.417).toInt())
        val hsv = org.opencv.core.Mat(); org.opencv.imgproc.Imgproc.cvtColor(roi, hsv, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV)
        val mask = org.opencv.core.Mat(); org.opencv.core.Core.inRange(hsv, org.opencv.core.Scalar(80.0, 80.0, 180.0), org.opencv.core.Scalar(105.0, 255.0, 255.0), mask)
        val frac = org.opencv.core.Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols()).coerceAtLeast(1)
        listOf(roi, hsv, mask, bgr).forEach { it.release() }
        return frac > 0.15
    }

    private val AD_CLOSE = Point(0.88, 0.03)
    /** The Another Dungeon map's "???" tab (it glows with a White Key: see [phantomTabGlows]). */
    private val AD_OTHER_TAB = Point(0.38, 0.11)

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
    ): Ocr.Line? = tapText(r.ctx, *wants, exact = exact, tries = tries, minY = minY, minX = minX, maxX = maxX)

    /** [tapText] outside a run (RiftDoor.kt): the same purchase guard. */
    internal fun tapText(
        ctx: Context, vararg wants: String, exact: Boolean = false, tries: Int = 3,
        minY: Double = 0.0, minX: Double = 0.0, maxX: Double = 1.0,
    ): Ocr.Line? {
        repeat(tries) {
            val lines = ctx.readText()
            val all = lines.joinToString(" ") { Ocr.norm(it.text) }
            buyWords(ctx).firstOrNull { it in all }?.let { w ->
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

    internal fun waitForField(ctx: Context, seconds: Int = 20): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) return true
            if (Battle.inBattle(ctx)) return false
            ctx.wait(0.3)
        }
        return false
    }
}
