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
 *  2. every map: follow its fixed path (dungeons.yaml `path:`, planned from the map's
 *     layout by `another-macro dungeon-routes`) to each chest and open it with the "!"
 *     bubble, fighting the encounters on the way, without opening the area map (see
 *     Route.kt); a map without a path walks its layout looking at the area map;
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
    data class MapSpec(val names: List<String>, val layout: AreaNav.Layout?, val chests: List<Point>, val exit: Point?, val boss: Boolean, val arrival: Point? = null, val chestsCount: Int = 0,
                       /** The fixed walk: one leg per chest (in order), then the exit. */
                       val path: List<Route.Leg>? = null) {
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
                (m["path"] as? List<*>)?.mapNotNull { (it as? List<*>)?.let { leg -> Route.parseLeg(leg) } }?.takeIf { it.isNotEmpty() },
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
            "fight all ${r.fightAll}, mobs ${r.mobs}, boss ${r.bossSetup}")
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
                r.map = idx; r.chestsDone = 0; r.cleared = false
                r.onBossMap = spec.maps[idx].boss
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

    /**
     * The dungeon panel's counters, left to right. One OCR line can hold two of them
     * ("5/5 En 16/50"): lines go by x, then the counters in a line by their order in it.
     */
    internal fun panelCounters(lines: List<Ocr.Line>): List<List<Int>> =
        lines.sortedBy { it.center.x }.flatMap { l ->
            // the denominators are known (10, 5, 50): "5/515/50" (no space read) is 5/5 and 15/50
            Regex("""(\d{1,2})\s*/\s*(50|10|5)(?=\D|$|\d{1,2}\s*/)""").findAll(l.text.replace(Regex("[Oo]"), "0").replace(Regex("[Il|]"), "1"))
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

    private fun noteItems(r: Run, lines: List<Ocr.Line>) {
        for ((name, n) in obtained(lines)) r.items[name] = (r.items[name] ?: 0) + n
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
                    .maxByOrNull { it.center.x }?.text?.let { cleanName(it) } ?: continue
                val n = num(c.text) ?: continue
                r.rewards[name] = (r.rewards[name] ?: 0) + n
            }
        }
        if ("items obtained" in all) {
            for (a in lines.filter { Ocr.norm(it.text).startsWith("amount") }) {
                val sameRow = lines.filter { abs(it.center.y - a.center.y) < 0.03 }.sortedBy { it.center.x }.joinToString(" ") { it.text }
                val n = Regex("""[Aa]mount\s*([0-9OoIl,]+)""").find(sameRow)?.groupValues?.get(1)?.let { num(it) } ?: continue
                val name = lines.filter { it.center.y < a.center.y && a.center.y - it.center.y < 0.1 && abs(it.center.x - a.center.x) < 0.2 &&
                    Regex("[A-Za-z]{3}").containsMatchIn(it.text) && !Regex("[0-9]").containsMatchIn(it.text) && !Ocr.norm(it.text).startsWith("items") }
                    .maxByOrNull { it.center.y }?.text?.let { cleanName(it) } ?: continue
                r.rewards[name] = n // the run's total of that item (shown once)
            }
        }
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
        val door = ctx.find(BUBBLE) ?: ctx.stop("no \"!\" over the Another Dungeon door: start next to it (Spacetime Rift)")
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
        tapText(r, r.spec.name, minY = 0.2, maxX = 0.62) ?: ctx.stop("${r.spec.name} not on the ${r.spec.era} map: ${seen(ctx)}")
        ctx.wait(2.0)
        // the panel's counters at the bottom: green keys, red keys, Cat Express Tickets ("17/50"); their "+" buy more
        // left to right: green keys "10/10", red keys "5/5", Cat Express Tickets "17/50"
        val bottom = LiveLog.without { ctx.readText() }.filter { it.center.y > 0.85 && it.center.x > 0.5 }
        // OCR reads 0 as O at times ("16/5O")
        val counters = panelCounters(bottom)
        // each counter by its denominator, never by its place (a counter OCR missed shifts the rest):
        // green keys "/10", red keys "/5", Cat Express Tickets "/50"
        val tickets = counters.firstOrNull { it[1] == 50 }?.get(0)
        val keys = counters.firstOrNull { it[1] == (if (red) 5 else 10) }?.get(0)
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
        if (openBubble(r)) { r.chestsDone++; ctx.count("chests"); return }
        // looking around moves the party off the chest's spot: read it on the map before the next leg
        r.unsure = r.pos != null
        for (k in 1..8) {
            val side = if (k % 2 == 1) dir else -dir
            val p = r.pos
            if (p != null && ms.layout != null) {
                // (on a path the position is followed through the search too)
                val t = Route.table(ctx)
                r.pos = Route.hold(ctx, ms.layout, t, p, side > 0, t.holdMs(0.006 * k).toLong()) { fight(r) }
            } else AreaNav.hold(ctx, side, 0.006 * k)
            fight(r)
            ctx.wait(0.6)
            if (openBubble(r)) { r.chestsDone++; ctx.count("chests"); return }
        }
        // (where the party stands on the map tells whether the chest's spot in dungeons.yaml is off)
        val at = if (r.pos != null) AreaNav.look(ctx) { emptyList() }.party else null
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
        fun resync(why: String): Boolean {
            val seen = AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
                .minByOrNull { abs(it.x - p.x) + 2 * abs(it.y - p.y) }
            Route.disturbed = false; r.unsure = false
            if (seen == null) { MacroLog.i("$why: no party marker on the map; keeping (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})"); return true }
            val seg = Route.segOf(l, seen)!!
            MacroLog.i("$why: the map shows (${"%.3f".format(seen.x)}, ${"%.3f".format(seen.y)}), followed (${"%.3f".format(p.x)}, ${"%.3f".format(p.y)})")
            p = Point(seen.x.coerceIn(seg.x1, seg.x2), seg.y)
            return seg == Route.segOf(l, plan)
        }
        /** Hold toward [x] on this street (into the wall when [x] is the street's end). */
        fun walkTo(x: Double) {
            val s = Route.segOf(l, p)
            var d = x - p.x
            if (s != null && (abs(x - s.x1) < 1e-6 || abs(x - s.x2) < 1e-6) && abs(d) >= t.minWalk) d += if (d > 0) t.wallMargin else -t.wallMargin
            if (abs(d) < t.minWalk) return
            p = Route.hold(ctx, l, t, p, d > 0, t.holdMs(abs(d)).toLong()) { fight(r) }
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
                    for (attempt in 1..3) {
                        // stairs only take the party standing on them
                        if (abs(p.x - plan.x) >= t.minWalk) walkTo(plan.x)
                        val before = ctx.refresh().gray.clone()
                        AreaNav.flick(ctx, st.up)
                        val after = ctx.refresh().gray.clone()
                        val change = Route.change(before, after)
                        before.release(); after.release()
                        val battle = fight(r)
                        MacroLog.i("flick ${st} (try $attempt): screen change ${"%.1f".format(change)}${if (battle) " (a battle came)" else ""}")
                        if (!battle && change >= t.flickChange) { took = true; break }
                        // check on the map: on the far end of the stairs, or still at their foot?
                        val seen = AreaNav.look(ctx) { emptyList() }.candidates.filter { Route.segOf(l, it) != null }
                            .minByOrNull { minOf(abs(it.x - next.x) + 2 * abs(it.y - next.y), abs(it.x - plan.x) + 2 * abs(it.y - plan.y)) }
                        Route.disturbed = false
                        if (seen != null && abs(seen.y - next.y) < AreaNav.ROW_TOL) { took = true; break }
                        if (seen != null && Route.segOf(l, seen) == Route.segOf(l, plan) && abs(seen.x - plan.x) < 0.03) {
                            // still at the stairs (a battle came first, or the flick missed them): flick again from here
                            MacroLog.i("still at the stairs (map shows x=${"%.3f".format(seen.x)}, stairs ${"%.3f".format(plan.x)}): again")
                            p = Point(seen.x, plan.y)
                            continue
                        }
                        r.pos = seen?.let { Point(it.x, Route.segOf(l, it)!!.y) } ?: p
                        offPath("the flick ${st} didn't take the stairs (map shows ${seen ?: "no marker"})")
                        return
                    }
                    if (!took) { r.pos = p; offPath("the flick ${st} didn't take the stairs in 3 tries"); return }
                    plan = next
                    p = next
                }
            }
            r.pos = p
        }
        r.pos = p
        r.planPos = plan
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
        val leg = ms.path?.firstOrNull { it.target == "exit" }
        if (leg != null && r.pos != null) walkLeg(r, ms, leg, exit) else AreaNav.walkLayout(ctx, ms.layout!!, exit, onStep(r))
        // the arrow shows on the ground once the party is near; walk on toward the exit until it does
        val toward = if (exit.x > 0.5) 1.0 else -1.0
        for (attempt in 0..7) {
            ctx.refresh()
            val arrow = ARROWS.mapNotNull { ctx.find(it) }.minByOrNull { abs(it.center.x - 0.5) }
            if (arrow != null) {
                MacroLog.i("taking the ground arrow at (${"%.2f".format(arrow.center.x)}, ${"%.2f".format(arrow.center.y)})")
                ctx.tap(arrow); ctx.wait(4.0)
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

    /** The round signs on the ground where a staircase/door leads to another map. */
    private val ARROWS = listOf("ground_arrow_bars", "ground_arrow_steps")

    /** Walk back and forth fighting until the game says no enemies are left (or none come any more). */
    private fun grind(r: Run, ms: MapSpec) {
        val ctx = r.ctx
        task(r, "${ms.names.first()}: fighting the enemies left (back and forth)")
        var dir = 1.0
        var quiet = 0
        // on a path: back and forth between two spots of this street, so the position stays known
        val l = ms.layout
        val seg = r.pos?.let { p -> l?.let { Route.segOf(it, p) } }
        val span = r.pos?.let { p -> seg?.let { s -> if (s.x2 - p.x >= p.x - s.x1) p.x to minOf(s.x2, p.x + 0.05) else maxOf(s.x1, p.x - 0.05) to p.x } }
        while (quiet < 12) {
            val p = r.pos
            var fought = false
            if (l != null && p != null && span != null) {
                val t = Route.table(ctx)
                val to = if (abs(p.x - span.first) < abs(p.x - span.second)) span.second else span.first
                if (abs(to - p.x) >= t.minWalk) r.pos = Route.hold(ctx, l, t, p, to > p.x, t.holdMs(abs(to - p.x)).toLong()) { fight(r).also { if (it) fought = true } }
            } else AreaNav.hold(ctx, dir, 0.05)
            dir = -dir
            if (fight(r) || fought) quiet = 0 else quiet++
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
            // after End the game is back on the Another Dungeon map: its "???" tab glows with a White Key
            if (lines.any { Ocr.norm(it.text) == "another dungeon" } && lines.any { Ocr.norm(it.text) == "hollow" }) {
                if (phantomTabGlows(ctx)) { if (!r.phantom) MacroLog.i("the ??? tab glows: White Key"); r.phantom = true }
                ctx.tap(AD_CLOSE); ctx.wait(2.0)
                return@repeat
            }
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
