package anothermacro.logic

import macroforge.MacroSteps
import macroforge.MacroSteps.Step

/**
 * The logic's own steps in plain words, for the app's "How it works" ([MacroSteps]): what the
 * code in this package does, read from the same data it runs on (a dungeon's maps, chests and
 * fixed route from its macro file's `dungeon:` block) and the options as set now. Kept next to the code it describes:
 * change a step there, change its words here (both ship in the logic pack).
 */
object HowItWorks {
    fun explain(action: String, arg: Any?, env: MacroSteps.Env): List<Step>? = when (action) {
        "another_dungeon" -> dungeon(arg.toString(), env)
        "phantom_crystal" -> phantom(env)
        "cat_daily" -> catDaily(env)
        "teleport" -> teleport(env.options)
        "fight" -> listOf(Step("Fight the battle on screen to its end: each party slot uses its skill from the options " +
            "(${skills(env.options, arg?.toString()?.takeIf { it != "true" } ?: "skill_")}); a defeat stops the macro"))
        "pick_party" -> listOf(Step("Tap Party; on the Party page tap the arrows until party ${env.options[arg.toString()]?.let { MacroSteps.shown(it) } ?: arg} " +
            "(its number in the game's list, read from the dots under it) is in front, then close the page"))
        "find_feather" -> listOf(Step("Look for the cat's feather bubble around the party and tap it"))
        "read_cat_clues" -> listOf(Step("Read today's clues in the Cat Scratchbook (on-screen text)"))
        "cat_measured" -> listOf(Step("Write every cat spot this phone measured to the log (one \"MEASURED cat spot\" line each), then stop; the game isn't needed"))
        else -> null
    }

    private fun on(v: String?, default: Boolean = false) = v?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on", "y") } ?: default

    /** "slot 1: skill 4, slots 2-4: Attack" from skill_1..4 (auto = Attack). */
    internal fun skills(o: Map<String, String>, prefix: String): String {
        val s = Battle.Setup.from(o, prefix).skills
        fun one(v: String) = if (v == "auto") "Attack" else "skill $v"
        return if (s.all { it == "auto" }) "all slots: plain Attack" else s.mapIndexed { i, v -> "slot ${i + 1}: ${one(v)}" }.joinToString(", ")
    }

    /** The battle macro's options this macro keeps for it (battle.skill_1 -> skill_1). */
    private fun battle(o: Map<String, String>) = o.filterKeys { it.startsWith("battle.") }.mapKeys { it.key.removePrefix("battle.") }

    // --- Another Dungeon ---------------------------------------------------------------------

    private fun dungeon(id: String, env: MacroSteps.Env): List<Step> {
        val o = env.options
        val text = env.file("macros/dungeon/$id.yaml") ?: return listOf(Step("Play the Another Dungeon run \"${id.replace('-', ' ')}\" (its data isn't downloaded yet)"))
        val spec = runCatching { AnotherDungeon.spec(text, id, AnotherDungeon.area(o)) }.getOrElse { return listOf(Step("Play the Another Dungeon run \"$id\" (${it.message})")) }
        val out = ArrayList<Step>()
        val opt = { keys: List<String> -> MacroSteps.optionsIn(keys.joinToString(" "), o) }

        // 1. the party
        val n = Party.number(o["party"])
        out += if (n != null) Step("Put party $n in front (Menu → Party), from the field", env.steps("pick-party", mapOf("party" to "$n")).orEmpty(),
            macro = "pick-party", note = opt(listOf("party")), label = "macro pick-party")
        else Step("Keep the party that is in front now", note = opt(listOf("party")))

        // 2. the runs
        val plan = AnotherDungeon.keyPlan(o)
        val keyNote = opt(listOf("red_key", "green_key", "runs", "all_keys"))
        if (plan.keys.isEmpty()) {
            out += Step("Stop: no key is switched on, so no run is done (only the party is picked)", note = keyNote)
            return out
        }
        fun keyName(k: String) = if (k == "red") "red keys (Very Hard)" else "green keys (Hard)"
        val all = plan.runs == Int.MAX_VALUE
        val head = plan.keys.joinToString(", then ") { k -> if (all) "runs with ${keyName(k)} until they run out" else "${plan.runs} run(s) with ${keyName(k)}" }
            .replaceFirstChar { it.uppercase() } + (if (spec.keyCost > 1) " (${spec.keyCost} keys a run)" else "") + ". Each run:"
        out += Step(head, run(spec, env), note = keyNote)

        // 3. the battles
        val bossOwn = on(o["boss_setup"])
        out += Step("Every battle: the battle macro with this macro's Battle options (${skills(battle(o), "skill_")})" +
            (if (bossOwn) "; the boss with the Boss skill options (${skills(o, "boss_skill_")})" else "; the boss too"),
            macro = "battle", note = opt(listOf("boss_setup")), label = "macro battle")
        // 4. the end
        out += Step("Stop with a report: runs done, time, chests and items found. Keys are never bought: the runs also end when the keys run out, " +
            "and any purchase offer stops the macro")
        return out
    }

    private fun run(spec: AnotherDungeon.Spec, env: MacroSteps.Env): List<Step> {
        val o = env.options
        val out = ArrayList<Step>()
        val opt = { keys: List<String> -> MacroSteps.optionsIn(keys.joinToString(" "), o) }
        out += toDoor(env, "inside ${spec.name}")
        val era = if (spec.era == "???") "\"???\"" else spec.era
        out += Step("Tap the \"!\" over the door, pick the $era tab" + (spec.area?.let { ", open $it" } ?: "") +
            ", tap ${spec.name} and pick the difficulty of the key (green: Hard, red: Very Hard)")
        out += Step("Check the key the game asks for (never bought; with none left these runs end). A White Key already held is dealt with first (White Key option)")
        val ticket = on(o["cat_ticket"])
        out += if (ticket) Step("Tap Skip with a Cat Express Ticket (when there is one): straight to the clear screen, the maps below are skipped; with no ticket, Move as usual",
            note = opt(listOf("cat_ticket")))
        else Step("Tap Move", note = opt(listOf("cat_ticket")))

        val clear = AnotherDungeon.defeatAll(o)
        val cap = o["clear_max_battles"]?.let { MacroSteps.shown(it) }?.takeIf { it != "0" }
        for ((i, m) in spec.maps.withIndex()) {
            val sub = ArrayList<Step>()
            // a map whose enemies run out: the ones the walk to the stops didn't meet, before the exit
            val enemiesLeft = if (m.enemies && !m.boss && m.layout != null && m.steps == null) {
                if (clear) Step("Every stop done: fight the enemies still left, walking back and forth, until the game says \"The enemies have disappeared\"" +
                    " (skipped when it already said so on the way)" + (cap?.let { " (at most $it battles)" } ?: ""), note = opt(listOf("defeat_all", "clear_max_battles")))
                else Step("Don't stop for the enemies left: straight to the exit once the stops are done", note = opt(listOf("defeat_all")))
            } else null
            if (m.enemies && !m.boss && m.layout != null && m.steps == null)
                sub += Step("Walk to the stops below at once, fighting the battles met on the way (after each one the party's spot is read again)")
            when {
                m.steps != null -> m.steps.forEach { sub += fixedStep(it, spec) }
                m.layout == null -> sub += Step("Look around the map and tap each \"!\": its chest, then ${boss(spec)}")
                m.path != null -> {
                    for (leg in m.path) {
                        if (leg.target == "exit") {
                            if (m.chests.isNotEmpty()) sub += Step("A chest not found on the way: one more try, walking to it by the area map")
                            enemiesLeft?.let { sub += it }
                        }
                        sub += leg(leg, o)
                    }
                }
                else -> {
                    if (m.sparkleIcons) sub += Step("Read where this run's sparkles are on the area map and pick each one up" +
                        (if (on(o["sparkles"], true)) "" else " (left out)"), skipped = !on(o["sparkles"], true), note = opt(listOf("sparkles")))
                    if (m.chests.isNotEmpty()) sub += Step("Walk to its ${m.chests.size} chest(s), nearest first, and open each one (a chest not found gets one more try before the exit)")
                    if (m.exit != null) enemiesLeft?.let { sub += it }
                    if (m.exit != null) sub += Step(if (m.exitWalk) "Walk off the edge of the map to the next one" else "Walk to the exit and take it")
                }
            }
            if (m.boss && m.steps == null && m.layout != null) sub += Step("Fight ${boss(spec)}")
            val title = "Map ${i + 1}: ${m.names.first()}" + if (m.boss) " (${boss(spec)})" else ""
            out += Step(title, sub)
        }
        out += Step("After the boss: read the rewards, tap through the result screens, and on \"Continue to skip?\" tap End")
        out += when (o["white_key"]?.trim()?.lowercase() ?: "phantom") {
            "stop" -> Step("If a White Key dropped: stop and leave the Phantom Crystal Dimension to you", note = opt(listOf("white_key")))
            "keep_going" -> Step("If a White Key dropped: ignore it and go on with the runs", note = opt(listOf("white_key")))
            else -> Step("If a White Key dropped: play the Phantom Crystal Dimension, then go on with the runs",
                env.steps("phantom-crystal").orEmpty(), macro = "phantom-crystal", note = opt(listOf("white_key")), label = "macro phantom-crystal")
        }
        return out
    }

    /** "the boss Ancient Sentry", or "the boss" when the data has no name. */
    private fun boss(spec: AnotherDungeon.Spec) = "the boss" + if (spec.boss.isNotBlank()) " ${spec.boss}" else ""

    /** One leg of a map's fixed route. */
    private fun leg(leg: Route.Leg, o: Map<String, String>): Step {
        fun collect(option: String, text: String): Step {
            val take = on(o[option], true)
            return Step(if (take) text else "$text: left out", skipped = !take, note = MacroSteps.optionsIn(option, o))
        }
        return when {
            leg.chest != null -> Step("Walk to chest ${leg.chest + 1} and open it")
            leg.sparkle != null -> collect("sparkles", "Walk to sparkle ${leg.sparkle + 1} and pick it up")
            leg.fish != null -> collect("fish", "Walk to fish ${leg.fish + 1} and throw the harpoon (one try)")
            leg.jar != null -> collect("jars", "Walk to bait jar ${leg.jar + 1} and empty it if it is full")
            leg.target == "exit" -> Step("Walk to the exit and take it to the next map")
            else -> Step("Walk to ${leg.target}")
        }
    }

    /** A small map's fixed step (the dungeon data's `steps:`). */
    private fun fixedStep(raw: String, spec: AnotherDungeon.Spec): Step {
        val p = raw.trim().split(Regex("\\s+"))
        fun dir() = if (p.getOrNull(1)?.uppercase() == "R") "right" else "left"
        return Step(when (p[0].uppercase()) {
            "BUBBLE" -> "Tap the \"!\" here (a chest or the way on)"
            "BOSS" -> "Walk ${dir()} to the boss, say Yes to \"Proceed?\" and fight ${boss(spec)}"
            "CHEST" -> "Walk ${dir()} until the chest's \"!\" shows and open it"
            "U" -> "Take the stairs up"
            "D" -> "Take the stairs down"
            "L", "R" -> "Walk ${if (p[0].uppercase() == "R") "right" else "left"}"
            else -> raw
        })
    }

    // --- World Map trips (Teleport.kt) --------------------------------------------------------

    private fun teleport(o: Map<String, String>): List<Step> {
        if (o["mode"] == "map_places") return listOf(
            Step("Open the World Map; for every era tab, every entry of its Expand list: sweep the whole map and " +
                "remember each place label with its era and continent (for later trips)", note = MacroSteps.optionsIn("mode", o)))
        val t = Teleport.Trip.of(o)
        val place = t.place.ifEmpty { "the place" }
        return listOf(
            Step("From the field: open the World Map and pick the ${t.era.ifEmpty { "era's" }} tab (when the tab doesn't open from the " +
                "continent in view: another continent first, then the tab)", note = MacroSteps.optionsIn("era", o)),
            Step((t.continent?.let { "Expand → $it, then look" } ?: "Look") + " for $place's label, panning the map" +
                (t.area?.let { " (inside $it's panel or mini map: that label first)" } ?: "") + "; not there: the map the tab " +
                "shows, then every entry of the Expand list (the continent found is remembered)", note = MacroSteps.optionsIn("continent area place", o)),
            Step("Tap the label" + (if (t.destination.isNotEmpty()) ", pick ${t.destination} in its panel" else "") +
                ", Yes, and wait for the field", note = MacroSteps.optionsIn("destination", o)),
        )
    }

    // --- Phantom Crystal Dimension -------------------------------------------------------------

    /** The way to the Another Dungeon door (RiftDoor.kt), by the go_to_door option. */
    private fun toDoor(env: MacroSteps.Env, inside: String): Step {
        val o = env.options
        val note = MacroSteps.optionsIn(RiftDoor.OPTION, o)
        return if (RiftDoor.enabled(o))
            Step("If not next to the Another Dungeon door or $inside: travel to the Spacetime Rift (\"???\" tab, Illusion World) " +
                "and walk left to the door", env.steps(Teleport.MACRO, RiftDoor.RIFT.params()).orEmpty(),
                macro = Teleport.MACRO, note = note, label = "macro ${Teleport.MACRO}")
        else Step("Start next to the Another Dungeon door or $inside: the macro doesn't travel there by itself", note = note)
    }

    private fun phantom(env: MacroSteps.Env) = listOf(
        toDoor(env, "inside the Phantom Crystal Dimension"),
        Step("Tap the \"!\" over the Another Dungeon door, the glowing \"???\" tab, Phantom Crystal Dimension, Hard (uses the White Key), Move"),
        Step("In every world: walk its fixed route (picked by the world's title, or by its place in the run when the title can't be read) " +
            "to every chest and cat, then the portal; a world with no route, or whose route loses its way, is looked around " +
            "instead, and the run's outcome tells you so"),
        Step("In every world: open every chest, tap the cats for their paw stamps, then take a portal (any \"!\" leading on)"),
        Step("World I has no chests: its king cat and notice board open the paw stamp menu, which is closed (claiming is left to you)"),
        Step("Bonus worlds move on by themselves once emptied"),
        Step("The last world: its chests, then stop before the character who offers a free Treatise/Codex/Opus, so you choose"),
        Step("The World's Cape: its chests and cat; if that character is there too, stop so you choose, otherwise take the stairs out"),
        Step("If the run stops for any other reason while still inside (e.g. no way on found): close any banner and start again from where the party stands, up to 3 times in 10 minutes; each resume is told in the run's outcome (RESUMED)"),
        Step("Still stuck after the resumes: open the Menu and tap Quit if it has it (the rest of the dimension is lost and the routine goes on); else end saying the party is still inside"),
    )

    // --- Cat Scratchbook ---------------------------------------------------------------------

    private fun catDaily(env: MacroSteps.Env) = listOf(
        Step("Open Diary → Cat Scratchbook and read today's clues"),
        Step("For each cat not found yet:", listOf(
            Step("Travel to the clue's place on the World Map (the spot's era, continent and place where cat_spots has it, else the clue's place)", env.steps(Teleport.MACRO).orEmpty(), macro = Teleport.MACRO, label = "macro ${Teleport.MACRO}"),
            Step("Where the way to the cat's map crosses maps with no area map (cat_spots `approach`), walk its fixed holds and flicks first"),
            Step("Walk to the cat's spot (a fixed path where known, else toward the feather on the area map)"),
            Step("Walk in short steps and tap the cat's feather bubble"),
            Step("Battles met on the way: the battle macro with this macro's Battle options " +
                "(${skills(battle(env.options), "skill_")})", macro = "battle", label = "macro battle"),
        )),
        Step("Back to the book after each cat (a cat that ran away leaves a new clue) until all are found; stop with the cats found and missed"),
    )
}
