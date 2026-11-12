package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.*

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.math.abs

/**
 * One run of an Another Dungeon (data in templates/another_dungeon/dungeons.yaml):
 *
 *  1. enter: the "!" over the Another Dungeon door (Spacetime Rift) → era tab → the
 *     dungeon's label → Hard (green key) or Very Hard (red key) → Move, or Skip when a
 *     Cat Express Ticket should be used (skips the whole dungeon);
 *  2. every map: first fight its enemies, walking back and forth by where the party arrived,
 *     until the game says they have disappeared (then no battle can interrupt the walk);
 *  3. follow the map's fixed path (dungeons.yaml `path:`, planned from the map's
 *     layout by `another-macro dungeon-routes`) to each chest and open it with the "!"
 *     bubble, fighting the encounters on the way, without opening the area map (see
 *     Route.kt); a map without a path walks its layout looking at the area map;
 *  4. walk to the map's exit and take it;
 *  5. the last map (no layout yet): sweep it, tapping every "!" (its chest, the boss);
 *     the boss battle gets the boss skill setup when one is chosen;
 *  6. stop when the run ends, and say so when a White Key opened the Phantom Crystal Dimension.
 *
 * Nothing that costs anything is ever confirmed: the key counters' "+" are key purchases
 * and are never tapped, and any purchase offer stops the run.
 */
object AnotherDungeon {
    data class MapSpec(val names: List<String>, val layout: AreaNav.Layout?, val chests: List<Point>, val exit: Point?, val boss: Boolean, val arrival: Point? = null, val chestsCount: Int = 0,
                       /** The fixed walk: one leg per chest (in order), then the exit. */
                       val path: List<Route.Leg>? = null,
                       /** False for a map without enemies (no fights first). */
                       val enemies: Boolean = true,
                       /** A small map done by fixed steps instead of a layout: "bubble", "U"/"D", "L 1000"/"R 1000", "boss L" (walk to the boss). */
                       val steps: List<String>? = null) {
        /** The same map drawn [dx] further right. */
        fun shifted(dx: Double) = copy(
            layout = layout?.let { l -> AreaNav.Layout(l.segs.map { it.copy(x1 = it.x1 + dx, x2 = it.x2 + dx) }, l.links.map { it.copy(x = it.x + dx) }) },
            chests = chests.map { Point(it.x + dx, it.y) }, exit = exit?.let { Point(it.x + dx, it.y) },
        )
    }
    data class Spec(
        val id: String, val name: String, val era: String, val boss: String, val maps: List<MapSpec>,
        /** A place on the era's map that opens a smaller map holding the dungeon ("Parallel Time Layer"). */
        val area: String? = null,
        /** The label's first line when the game wraps the name ("Burning Beast King's" / "Castle"). */
        val label: String? = null,
    )

    private const val DATA = "templates/another_dungeon/dungeons.yaml"
    private const val BUBBLE = "field_interact_bubble"
    /** The bubble's inside only, for bright or busy backgrounds (the Throne Room's light pillar and fires). */
    private const val BUBBLE_CORE = "field_interact_bubble_core"
    /** How far the party walks each way while clearing a map's enemies (area-map units). */
    private const val GRIND_SPAN = 0.09

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
                (m["path"] as? List<*>)?.mapNotNull { (it as? List<*>)?.let { leg -> Route.parseLeg(leg) } }?.takeIf { it.isNotEmpty() },
                m["enemies"] != false,
                (m["steps"] as? List<*>)?.map { it.toString() },
            )
        }
        return Spec(id, d["name"].toString(), d["era"].toString(), d["boss"]?.toString() ?: "", maps, d["area"]?.toString(), d["label"]?.toString())
    }

    private class Run(val ctx: Context, val spec: Spec) {
        // every fight is the basic macro battle's step with its skills; the boss with the dungeon's own
        // boss skills when set. (Done here, not by running that macro: the run must tell the boss by its
        // name on the first turn and read the White Key off the results while they show.)
        val mobs = Battle.Setup.from(ctx.optionsOf("battle"), "skill_")
        val bossSetup = if (ctx.bool("boss_setup")) Battle.Setup.from(ctx.params, "boss_skill_") else mobs
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
        /** The chosen key ran out (read on the dungeon panel): the loop ends. */
        var outOfKeys = false
        /** The macro's counters when this run started. */
        val base: Map<String, Int> = HashMap(ctx.stats)
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
        /** A BannerWatch is reading the whole screen for "the enemies have disappeared" (no extra look after a win needed). */
        var watching = false
        /** The direction of the last walk on the path (+1 right, -1 left): a chest is looked for that way first. */
        var lastDir = 0.0
    }

    private fun task(r: Run, text: String) { r.task = text; MacroLog.status(text) }

    /**
     * Run the dungeon [runs] times in a row ("all": until the chosen key runs out). After every
     * run a White Key is checked for: `white_key` = phantom runs the Phantom Crystal Dimension
     * and comes back to the loop, stop ends the loop, keep_going ignores it.
     */
    fun run(ctx: Context, id: String): String {
        val spec = spec(ctx, id)
        val want = ctx.params["runs"]?.trim()?.lowercase()?.removeSuffix(".0") ?: "1"
        val total = if (want == "all") Int.MAX_VALUE else want.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val onWhiteKey = ctx.params["white_key"]?.trim()?.lowercase() ?: "phantom"
        val start = System.currentTimeMillis()
        val lines = ArrayList<String>()
        var done = 0
        var phantoms = 0
        var ignore = false
        var end = ""
        while (done < total) {
            if (total > 1) MacroLog.status("${spec.name}: run ${done + 1}${if (total == Int.MAX_VALUE) "" else "/$total"}")
            val r = Run(ctx, spec).also { it.ignoreHeldKey = ignore }
            val outcome = try { runInner(r) } catch (s: StopScenario) { s.message ?: "stopped" }
            if (r.outOfKeys) { end = outcome; break }
            if (r.heldKey && !r.entered) {
                // a White Key from before: deal with it first, then this run
                when (onWhiteKey) {
                    "phantom" -> { if (!phantom(ctx, lines)) { end = "the Phantom Crystal Dimension run failed"; break }; phantoms++; continue }
                    "stop" -> { end = outcome; break }
                    else -> { ignore = true; continue }
                }
            }
            val ok = outcome.startsWith("${spec.name} done") || r.phantom
            // a run that stopped before entering (or skipping) isn't a run
            if (!ok && !r.entered && !r.skipped) { end = if (done > 0) "stopped after run $done: $outcome" else outcome; break }
            lines += "run ${done + 1}: " + report(r, outcome)
            done++
            ctx.count("runs")
            if (!ok) { end = "stopped after run $done: $outcome"; break }
            if (r.phantom) when (onWhiteKey) {
                "phantom" -> { if (!phantom(ctx, lines)) { end = "the Phantom Crystal Dimension run failed"; break }; phantoms++ }
                "stop" -> { end = "a White Key dropped: stopping for you (Phantom Crystal Dimension open)"; break }
                else -> ignore = true
            }
        }
        if (total == 1 && lines.size == 1 && phantoms == 0 && end.isEmpty()) return lines[0].substringAfter(": ")
        if (total == 1 && lines.isEmpty() && phantoms == 0) return end
        val mins = (System.currentTimeMillis() - start) / 60000
        val summary = "${spec.name}: $done run(s)" + (if (phantoms > 0) ", $phantoms Phantom Crystal Dimension run(s)" else "") +
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
        MacroLog.i("${spec.name}: key ${ctx.str("key")}, cat ticket ${ctx.bool("cat_ticket")}, " +
            "mobs ${r.mobs}, boss ${r.bossSetup}")
        // started on the boss gate's question, or outside: else already inside
        if (!bossGate(r) && whereAmI(r) == null && !Battle.inBattle(ctx)) {
            enter(r)?.let { return it }
        }
        repeat(300) {
            if (r.phantom) return phantomDone(r)
            val idx = r.nextMap ?: if (r.map >= 0 && !r.moved) r.map else whereAmI(r)
            r.moved = false
            r.nextMap = null
            if (idx == null) {
                if (fight(r)) return@repeat
                if (bossGate(r)) return@repeat
                // not on one of the dungeon's maps any more: the run ended
                return finish(r)
            }
            if (idx != r.map) {
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
                    r.shift = if (arrival != null && party != null && abs(party.y - arrival.y) < AreaNav.ROW_TOL && abs(party.x - arrival.x) < 0.025)
                        party.x - arrival.x else 0.0
                    MacroLog.i("layout offset ${"%.3f".format(r.shift)}")
                    r.pos = null; r.planPos = null
                    r.order = chestOrder(spec.maps[idx].shifted(r.shift), party)
                }
                // clear_first off: no grinding; the path is walked at once and the battles on the way are
                // fought where they come (the walk reads its spot on the area map after each one)
                r.map = idx; r.chestsDone = 0; r.cleared = !spec.maps[idx].enemies || !clearFirst(ctx)
                r.onBossMap = spec.maps[idx].boss
                r.chestsTotal += spec.maps[idx].chests.size.takeIf { it > 0 } ?: spec.maps[idx].chestsCount
                task(r, "Map ${idx + 1}/${spec.maps.size}: ${spec.maps[idx].names.first()}")
                ctx.count("maps")
            }
            val ms = spec.maps[idx].shifted(r.shift)
            when {
                ms.steps != null -> runSteps(r, ms)
                ms.layout == null -> sweep(r)
                // every map's enemies first: once "the enemies have disappeared", no battle can cut
                // the fixed path short, so it is walked exactly as planned
                !r.cleared && !r.onBossMap -> {
                    grind(r, ms)
                    // the fights moved the party a little: read its spot once before the path
                    if (r.pos != null) { r.unsure = true; r.lastDir = 0.0 }
                }
                r.chestsDone < ms.chests.size -> takeChest(r, ms, ms.chests[r.order.getOrElse(r.chestsDone) { r.chestsDone }])
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

    /**
     * The dungeon panel's counters, left to right. One OCR line can hold two of them
     * ("5/5 En 16/50"): lines go by x, then the counters in a line by their order in it.
     */
    /** The chosen key's count and the Cat Express Tickets' count from [panelCounters]; null when not read. */
    internal fun keysAndTickets(counters: List<List<Int>>, red: Boolean): Pair<Int?, Int?> {
        val tickets = counters.firstOrNull { it[1] == 50 }?.get(0)
        val keys = if (red) counters.firstOrNull { it[1] == 5 }?.get(0)
            else counters.firstOrNull { it[1] != 5 && it[1] != 50 }?.get(0)
        return keys to tickets
    }

    internal fun panelCounters(lines: List<Ocr.Line>): List<List<Int>> =
        lines.sortedBy { it.center.x }.flatMap { l ->
            // the denominators are 50, 10 or one digit (green keys "5/9", red "4/5"): "5/515/50" (no space
            // read) is 5/5 and 15/50
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
        val door = doorBubble(ctx) ?: toRiftDoor(r) ?: ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift)")
        ctx.tap(door); ctx.wait(2.5)
        // the map fades in: give it a few reads
        var opened = false
        for (i in 0 until 6) {
            if (ctx.readText().any { Ocr.norm(it.text) == "another dungeon" }) { opened = true; break }
            ctx.wait(1.0)
        }
        if (!opened) ctx.stop("the door didn't open the Another Dungeon map: ${seen(ctx)}")
        if (!r.ignoreHeldKey && phantomTabGlows(ctx)) {
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            r.heldKey = true
            return "a White Key is held (the Phantom Crystal Dimension is open): not entering"
        }
        tapText(r, eraLabel(r.spec.era)); ctx.wait(1.5)
        // a dungeon inside a place of the era's map: open that place's map first (a box over the
        // map; the dungeon's label can sit right of the middle there)
        val area = r.spec.area
        if (area != null) {
            mapLabel(r, arrayOf(area), 0.62) ?: ctx.stop("$area not on the ${r.spec.era} map: ${seen(ctx)}")
            ctx.wait(1.5)
        }
        mapLabel(r, listOfNotNull(r.spec.name, r.spec.label).toTypedArray(), if (area != null) 0.85 else 0.62)
            ?: ctx.stop("${r.spec.name} not on the ${r.spec.era} map${area?.let { " ($it)" } ?: ""}: ${seen(ctx)}")
        ctx.wait(2.0)
        // the panel's counters at the bottom: green keys, red keys, Cat Express Tickets ("17/50"); their "+" buy more
        // left to right: green keys "10/10", red keys "5/5", Cat Express Tickets "17/50"
        val bottom = LiveLog.without { ctx.readText() }.filter { it.center.y > 0.85 && it.center.x > 0.5 }
        // OCR reads 0 as O at times ("16/5O")
        val counters = panelCounters(bottom)
        // each counter by its denominator, never by its place (a counter OCR missed shifts the rest):
        // red keys "/5", Cat Express Tickets "/50", green keys the other one ("/10", "/9")
        val (keys, tickets) = keysAndTickets(counters, red)
        if (keys == null || tickets == null || counters.size < 3) MacroLog.i("panel counters read: ${bottom.joinToString(" | ") { it.text }} → $counters")
        MacroLog.i("keys (${if (red) "red" else "green"}): ${keys ?: "not read"}; Cat Express Tickets: ${tickets ?: "not read"}")
        if (keys == 0) {
            // the game would offer to buy keys: never; the loop ends here
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            r.outOfKeys = true
            return "out of ${if (red) "red" else "green"} keys"
        }
        val skip = ctx.bool("cat_ticket") && (tickets ?: 0) > 0
        if (ctx.bool("cat_ticket") && !skip) MacroLog.i("no Cat Express Ticket to use: walking the dungeon instead")
        tapText(r, difficulty, exact = true, minX = 0.6) ?: ctx.stop("no '$difficulty' entry for ${r.spec.name}: ${seen(ctx)}")
        ctx.wait(2.0)
        // "Using Green Key×1 to move to Dogma Tower (Hard)."
        val lines = ctx.readText()
        // "In order to move to Dogma Tower (Hard) please include: <a character>": Move stays greyed out
        val include = lines.indexOfFirst { Ocr.norm(it.text).contains("please include") }
        if (include >= 0) {
            val who = lines.getOrNull(include + 1)?.text?.takeIf { lines[include].text.trimEnd().endsWith(":") }
                ?: lines[include].text.substringAfter(":", "").trim()
            tapText(r, "Back", exact = true)
            ctx.stop("the party must include ${who.ifEmpty { "a character the game names" }}: add them to the party in front, then start again")
        }
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

    /**
     * Tap a place's label on the Another Dungeon map. The map keeps its last pan, so a label can sit
     * half hidden under the era icons at the top right ("Dogma TovSealed") or cut by the left edge
     * ("lel Time Layer"): then drag what shows of it (else the map) toward the middle and look again.
     */
    /** The "!" over the Another Dungeon door, not too far from the party (a "!" over a tree NPC sits higher). */
    private fun doorBubble(ctx: Context): Point? =
        ctx.find(BUBBLE)?.center?.takeIf { it.y > DOOR_MIN_Y }

    /**
     * Not next to the door: World Map → "???" → Spacetime Rift → Yes, then walk left from the
     * arrival point until the door's "!" shows near the middle. Null if that didn't work.
     */
    private fun toRiftDoor(r: Run): Point? {
        val ctx = r.ctx
        task(r, "Going to the Another Dungeon door (Spacetime Rift)")
        var opened = false
        for (attempt in 1..3) {
            ctx.tap(MAP_BUTTON); ctx.wait(2.5)
            if (ctx.readText().any { Ocr.norm(it.text) == "world map" }) { opened = true; break }
            waitForField(ctx, 5)
        }
        if (!opened) return null.also { MacroLog.i("the world map didn't open") }
        ctx.tap(ERA_OTHER); ctx.wait(2.0)
        if (tapText(r, "Spacetime Rift") == null) {
            MacroLog.i("no Spacetime Rift on the map: ${seen(ctx)}")
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            return null
        }
        ctx.wait(1.5)
        val yes = ctx.readText().firstOrNull { Ocr.norm(it.text) == "yes" && it.center.y > 0.4 }
        if (yes != null) {
            ctx.tapExact(yes.center)
            if (!waitForField(ctx, 30)) return null.also { MacroLog.i("the field didn't come back after the trip") }
        } else {
            // already in the Rift: the map just closes
            ctx.tap(AD_CLOSE); ctx.wait(1.5)
            waitForField(ctx, 5)
        }
        ctx.wait(1.0)
        // the door is a short walk left of the arrival point
        repeat(16) {
            ctx.refresh()
            doorBubble(ctx)?.let { if (it.x in 0.25..0.75) { MacroLog.i("at the Another Dungeon door"); return it } }
            ctx.drag(Point(0.5, 0.65), Point(0.4, 0.65), RIFT_STEP_MS)
            ctx.wait(0.5)
        }
        return null.also { MacroLog.i("walked left in the Rift and found no door \"!\"") }
    }

    private fun mapLabel(r: Run, wants: Array<String>, maxX: Double): Ocr.Line? {
        val ctx = r.ctx
        tapText(r, *wants, minY = 0.2, maxX = maxX, tries = 2)?.let { return it }
        val words = Ocr.norm(wants.first()).split(" ").filter { it.length >= 4 }
        // a few drags: the map keeps its last pan, so the label can be off either side or at the
        // right edge under this app's floating buttons ("Dogma Towe" at x 0.93). When part of it
        // reads, drag that part to the middle; else drag blind: left once, then right three times
        // (back to the start and on past it), so a label off either side comes into view
        val blind = listOf(-1, 1, 1, 1)
        for (attempt in blind.indices) {
            val part = ctx.readText().firstOrNull { l -> val t = Ocr.norm(l.text); words.isNotEmpty() && words.count { it in t } >= (words.size + 1) / 2 }
            MacroLog.i("${wants.first()} label not in the clear (${part?.text ?: "not seen"}): dragging the map")
            // blind drags go over open sea: one that starts on the islands in the middle (0.49, 0.65)
            // doesn't pan the Present map (seen 2026-11-11); each moves it about a third of the screen
            if (part != null) ctx.drag(part.center, Point(0.45, 0.5), 700)
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
        // "Dogma Tower - Gloomy Corridor": the dungeon's name, then the map's. Some dungeons title
        // their maps on their own ("Beast King's Castle Front Guard Tower"): then the whole title
        // must read as one of the map names
        val named = Ocr.norm(title).contains(Ocr.norm(r.spec.name))
        val part = if (named) title.substringAfter("-").trim() else title.trim()
        val best = r.spec.maps.indices.maxByOrNull { i -> r.spec.maps[i].names.maxOf { Ocr.similarity(part, it) } } ?: return null
        return if (r.spec.maps[best].names.maxOf { Ocr.similarity(part, it) } > (if (named) 0.6 else 0.85)) best else null
    }

    /** Where a map's title shows as it opens ("HARD" over "Beast King's Castle Watchtower"). */
    private val ARRIVAL_TITLE = Region(0.15, 0.05, 0.7, 0.2)

    /**
     * Watch the arrival title for up to [seconds]: the index of a map other than [from] that it
     * names, or null. Only a whole-title match counts (castle maps share most of their words).
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
            AreaNav.walkLayout(ctx, ms.layout!!, chest, onStep(r))
            AreaNav.look(ctx) { emptyList() }.party
        }
        // close by: the chest's "!" shows; look around the spot, a little further each side, until it does
        val dir = if (party != null && chest.x < party.x) -1.0 else 1.0
        // the "!" pops up a while after the party stops: watch for it before looking around
        if (waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); return }
        // not there: read the real spot on the map and walk to the chest's x from it (a hold can
        // come up short, a battle can cut it), then look again
        val l0 = ms.layout
        val p0 = r.pos
        if (l0 != null && p0 != null) {
            val t = Route.table(ctx)
            repeat(4) {
                // a battle can start just as the party stops by the chest (no "!" then, and no area
                // map to read): fight it first, then look for the "!" again
                if (fight(r) && waitBubble(r, 3.0)) { r.chestsDone++; ctx.count("chests"); return }
                val at = readSpot(r, l0, r.pos ?: p0) ?: return@repeat
                MacroLog.i("no \"!\" yet: the map shows the party at (${"%.3f".format(at.x)}, ${"%.3f".format(at.y)}), chest at ${"%.3f".format(chest.x)}")
                r.pos = at
                val d = chest.x - at.x
                if (Route.segOf(l0, at) != Route.segOf(l0, chest) || abs(d) < 0.004) return@repeat
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
                val t = Route.table(ctx)
                r.pos = Route.hold(ctx, ms.layout, t, p, side > 0, t.holdMs(0.006).toLong()) { fight(r) }
            } else AreaNav.hold(ctx, side, 0.006)
            fight(r)
            if (waitBubble(r, 1.5)) { r.chestsDone++; ctx.count("chests"); return }
        }
        // (where the party stands on the map tells whether the chest's spot in dungeons.yaml is off)
        val at = r.pos?.let { p -> ms.layout?.let { readSpot(r, it, p) } }
        // a picture of the screen for working out why (the app's own storage, never shared)
        runCatching {
            ctx.colorMat()?.let { m ->
                val f = java.io.File(ctx.debugDir, "no-chest-map${r.map + 1}-${r.chestsDone + 1}.png")
                org.opencv.imgcodecs.Imgcodecs.imwrite(f.path, m); m.release()
                MacroLog.i("saved ${f.name} in the app's debug folder")
            }
        }
        MacroLog.i("no chest found by (${"%.3f".format(chest.x)}, ${"%.3f".format(chest.y)}); skipping it" +
            (at?.let { " (the map shows the party at (${"%.3f".format(it.x)}, ${"%.3f".format(it.y)}))" } ?: ""))
        r.unsure = r.pos != null
        r.chestsDone++
    }

    /**
     * Follow one leg of the map's path. Each hold aims at the spot the plan puts it at, from
     * where the party really is (a battle can cut a hold short), so the stored ms are what's
     * held when nothing gets in the way. A flick that seems to have done nothing (the screen
     * barely changed) is checked on the area map; when the party isn't where the plan has it,
     * the rest of the leg is walked the old way (looking at the area map).
     */
    private fun walkLeg(r: Run, ms: MapSpec, leg: Route.Leg, goal: Point) {
        val ctx = r.ctx
        val l = ms.layout!!
        val t = Route.table(ctx)
        var p = r.pos!!
        var plan = r.planPos ?: p
        MacroLog.i("path to ${leg.target}: ${leg.steps.joinToString(", ")}")
        fun offPath(why: String) {
            MacroLog.i("$why: walking to ${leg.target} by the area map instead")
            AreaNav.walkLayout(ctx, l, goal, onStep(r))
            r.pos = Point(goal.x, Route.segOf(l, goal)?.y ?: goal.y)
            r.planPos = r.pos
        }
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
            // a battle the hold didn't see (it came anyway): where the party stopped is unknown, read it and walk the rest.
            // Walking straight to the chests (clear_first off) a long hold can be cut by 3-4 battles in a row
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
        if (r.unsure && !resync("after looking around for the chest")) { r.pos = p; offPath("not on the street the path expects"); return }
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
                    val next = Route.climbed(l, plan, st.up) ?: run { r.pos = p; offPath("the path has no stairs ${if (st.up) "up" else "down"} here"); return }
                    var took = false
                    // the stairs can be a little off in the data: from the 2nd try, flick a little either side
                    val offsets = listOf(0.0, -0.006, 0.006, -0.012, 0.012)
                    for (attempt in 1..offsets.size) {
                        // stairs only take the party standing on them
                        val at = plan.x + offsets[attempt - 1]
                        if (abs(p.x - at) >= t.minWalk) walkTo(at)
                        // a battle on the walk to the stairs: read where it left the party and walk on to them
                        // before flicking (a flick from the wrong spot is a wasted try)
                        // An encounter often starts just as a hold ends (seen 2026-11-12, about every other
                        // flick on the castle): stand still a moment, fight it first, then flick from the real spot
                        for (again in 1..4) {
                            ctx.wait(0.4) // standing still on the stairs before the flick
                            if (fight(r)) Route.disturbed = true
                            if (!Route.disturbed) break
                            if (!resync("on the way to the stairs after a battle", at)) { r.pos = p; offPath("not on the street the path expects"); return }
                            if (abs(p.x - at) >= t.minWalk) walkTo(at)
                        }
                        val before = ctx.refresh().gray.clone()
                        AreaNav.flickSettled(ctx, st.up)
                        val after = ctx.refresh().gray.clone()
                        val change = Route.change(before, after)
                        before.release(); after.release()
                        val battle = fight(r)
                        MacroLog.i("flick ${st} (try $attempt): screen change ${"%.1f".format(change)}${if (battle) " (a battle came)" else ""}")
                        if (!battle && change >= t.flickChange) {
                            took = true
                            if (attempt > 1) MacroLog.i("stairs ${st} taken at x≈${"%.3f".format(p.x)} (data ${"%.3f".format(plan.x)}): fix the link in dungeons.yaml")
                            break
                        }
                        // check on the map: on the far end of the stairs, or still at their foot?
                        val seen = Route.seen(ctx, l, listOf(next, Point(p.x, plan.y)), "after the flick ${st}") { AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
                            .minByOrNull { minOf(abs(it.x - next.x) + 2 * abs(it.y - next.y), abs(it.x - plan.x) + 2 * abs(it.y - plan.y)) } }
                        Route.disturbed = false
                        if (seen != null && abs(seen.y - next.y) < AreaNav.ROW_TOL) {
                            took = true
                            if (attempt > 1) MacroLog.i("stairs ${st} taken at x≈${"%.3f".format(p.x)} (data ${"%.3f".format(plan.x)}): fix the link in dungeons.yaml")
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
    private fun openBubble(r: Run): Boolean {
        val ctx = r.ctx
        ctx.refresh()
        val b = ctx.find(BUBBLE) ?: ctx.find(BUBBLE_CORE) ?: return false
        ctx.tap(b); ctx.wait(1.0)
        // the banner can be a moment late (the read came back empty): read again for up to ~3 s
        var read = ctx.readText()
        for (i in 0 until 6) {
            val all = read.joinToString(" ") { Ocr.norm(it.text) }
            if ("obtained" in all || "proceed" in all || "major trial" in all) break
            ctx.refresh()
            if (Battle.inBattle(ctx)) break
            ctx.wait(0.5)
            read = ctx.readText()
        }
        val text = read.joinToString(" ") { it.text }
        MacroLog.i("\"!\": ${text.take(120)}")
        noteItems(r, read)
        // a chest found while looking around a map without a layout
        if (Ocr.norm(text).contains("obtained") && r.spec.maps.getOrNull(r.map)?.layout == null) ctx.count("chests")
        if (bossGate(r)) { fight(r); return true }
        // a chest's "Obtained ...": close it and go on as soon as the field is back (a battle check
        // on the banner itself waits ~5 s for a battle that isn't coming)
        if ("obtained" in Ocr.norm(text)) {
            ctx.tap(Point(0.5, 0.75)); ctx.wait(0.5)
            if (!waitForField(ctx, 6)) fight(r)
            return true
        }
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
        val leg = ms.path?.firstOrNull { it.target == "exit" }
        if (leg != null && r.pos != null) walkLeg(r, ms, leg, exit) else AreaNav.walkLayout(ctx, ms.layout!!, exit, onStep(r))
        // the arrow shows on the ground once the party is near; walk on toward the exit until it does
        val toward = if (exit.x > 0.5) 1.0 else -1.0
        for (attempt in 0..7) {
            ctx.refresh()
            val arrow = ARROWS.mapNotNull { ctx.find(it) }.minByOrNull { abs(it.center.x - 0.5) }
            if (arrow != null) {
                MacroLog.i("taking the ground arrow at (${"%.2f".format(arrow.center.x)}, ${"%.2f".format(arrow.center.y)})")
                ctx.tap(arrow); ctx.wait(1.5)
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
            when (attempt) {
                3 -> AreaNav.flick(ctx, up = false)
                5 -> AreaNav.flick(ctx, up = true)
                else -> AreaNav.hold(ctx, if (attempt < 3) -toward else toward, 0.008)
            }
            fight(r)
        }
        MacroLog.i("the exit didn't take us anywhere yet")
    }

    /** Fights on one map before it is taken as cleared even without the banner (a safety cap). */
    internal const val CLEAR_MAX_BATTLES = 15

    /** The round signs where a staircase/door leads to another map (on the ground, or a door sign over it). */
    private val ARROWS = listOf("ground_arrow_bars", "ground_arrow_steps", "door_sign")

    /**
     * Walk back and forth fighting (like grind-battles with until_cleared) until the game says
     * "The enemies have disappeared" (the user's rule: every enemy first). Safety caps for a missed
     * banner: [clear_max_battles] fights won on this map (15 by default, 0 = no cap), or no
     * encounter in 12 walks.
     */
    private fun grind(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        task(r, "${ms.names.first()}: fighting the enemies first (back and forth)")
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
        BannerWatch(r).use { watch ->
            while (quiet < 12) {
                if (watch.check()) return
                val p = r.pos
                var fought = false
                if (l != null && p != null && span != null) {
                    val t = Route.table(ctx)
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
        }
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
    /** Fight every map until "The enemies have disappeared" before its chests (the default), or not. */
    private fun clearFirst(ctx: Context) = ctx.params["clear_first"]?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on", "y") } ?: true

    internal fun noEnemiesLeft(text: String) = "disappear" in text

    /** Where "The enemies have disappeared" shows: the top middle (kept wide: its exact spot is not measured). */
    private val BANNER = Region(0.05, 0.0, 0.9, 0.45)

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
        if (!r.cleared && !r.onBossMap) { grind(r, r.spec.maps[r.map]); return }
        task(r, "Map ${r.map + 1}: looking around (chest, boss)")
        val ms = r.spec.maps[r.map]
        val hasChests = ms.chests.isNotEmpty() || ms.chestsCount > 0
        for (vertical in listOf(null, true, false)) {
            if (vertical != null) { AreaNav.flick(ctx, vertical); fight(r); if (r.bossBeaten) return }
            for (dir in listOf(-1.0, 1.0)) {
                var still = 0
                var last: Point? = null
                for (step in 0 until 30) {
                    if (bossGate(r)) { fight(r); if (r.bossBeaten) return }
                    // a map without chests (Deliverance Sword): its "!" only leads back, leave it be
                    if (hasChests && openBubble(r)) {
                        ctx.count("bubbles")
                        if (r.bossBeaten) return
                        // a chest, or a portal to the next map (Throne Room's light pillar): look again
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
     * A small map by its fixed steps (Throne Room: the chest's "!" by the arrival, up to the light
     * pillar, its "!" to the next map). Then the map is read again: if the party is still here the
     * steps run again (a chest already open shows no "!", so it costs only a look).
     */
    private fun runSteps(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        task(r, "Map ${r.map + 1}: ${ms.names.first()}")
        for (step in ms.steps!!) {
            val parts = step.trim().split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "BUBBLE" -> {
                    var tapped = false
                    for (i in 0 until 3) { if (openBubble(r)) { tapped = true; break }; ctx.wait(0.7) }
                    if (!tapped) MacroLog.i("step bubble: no \"!\" here")
                }
                // "boss L": walk that way in plain holds until the boss's question shows, then fight it
                // (Burning Beast King's Castle: the boss area has no chest, so no looking around)
                "BOSS" -> {
                    val dir = if (parts.getOrNull(1)?.uppercase() == "R") 1.0 else -1.0
                    for (i in 0 until 20) {
                        if (bossGate(r)) { fight(r); break }
                        if (fight(r) && r.bossBeaten) break
                        ctx.drag(Point(0.5, 0.65), Point(0.5 + 0.1 * dir, 0.65), 1500)
                        ctx.wait(0.5)
                    }
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
        r.moved = true
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
                // "The enemies have disappeared" flashes for a moment after a map's last enemy: a short
                // look here (while a map is cleared, BannerWatch keeps looking as the party walks on)
                val end = System.currentTimeMillis() + 1000
                val seen = LinkedHashSet<String>()
                while (!r.watching && !r.cleared && !r.bossBeaten && System.currentTimeMillis() < end) readAfterWin(r, quick = true).takeIf { it.isNotBlank() }?.let { seen += it }
                // for tuning the banner's spot and timing: what the top of the screen said meanwhile
                if (!r.cleared && !r.onBossMap && seen.isNotEmpty()) MacroLog.i("top after the win: ${seen.joinToString(" | ").take(160)}")
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
        var rewardWaits = 0
        repeat(25) {
            ctx.refresh()
            if (ctx.find("field_menu_button") != null) {
                if (whereAmI(r) != null) return "${r.spec.name}: the boss is beaten but the party is still inside"
                return if (r.phantom) phantomDone(r) else "${r.spec.name} done"
            }
            if (Battle.inBattle(ctx)) { fight(r); return@repeat }
            val lines = ctx.readText()
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
    private val MAP_BUTTON = Point(0.385, 0.917)
    /** The World Map's "???" tab (Spacetime Rift, Nopaew Emporium, ...). */
    private val ERA_OTHER = Point(0.379, 0.17)
    private const val DOOR_MIN_Y = 0.13
    private const val RIFT_STEP_MS = 350L

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
            ctx.wait(0.3)
        }
        return false
    }
}
