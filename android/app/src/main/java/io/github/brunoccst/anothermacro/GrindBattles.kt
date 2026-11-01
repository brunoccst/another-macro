package io.github.brunoccst.anothermacro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Port of the Python grind-battles: walk back and forth on the current map,
 * fight every encounter (skill mode: the first character's last skill, then
 * Attack; or auto), clear the results, repeat.
 */
class GrindBattles : Scenario() {
    override val name = "grind-battles"
    override val description = "Walk back and forth on the current map, fight every encounter, repeat."
    override val templateFolders = listOf("grind_battles")
    override val params = linkedMapOf(
        "battles" to Param("10", "stop after this many battles (0 = no limit)"),
        "walk_ms" to Param("1500", "how long each walk in one direction lasts"),
        "first_walk" to Param("-1", "direction of the first walk: -1 = left, 1 = right"),
        "stuck_walks" to Param("8", "stop if this many walks in a row leave the view unchanged (0 = never)"),
        "mode" to Param("skill", "skill = set one character's skill then Attack; auto = Auto button"),
        "slot" to Param("1", "skill mode: front-row position (1-4) of the character whose skill is set"),
        "skill_x" to Param("0.8075", "skill mode: x (0..1) of the skill to pick in the skill row"),
    )
    override val screens = listOf("title", "defeat", "battle_result", "skill_list", "battle_command", "battle", "field")

    private var direction = -1
    private var lastView: Mat? = null
    private var walked = false
    private var stillWalks = 0
    private var inBattle = false
    private var skillSet = false

    override fun setup(ctx: Context) {
        ctx.stats["battles"] = 0
        direction = ctx.int("first_walk")
    }

    override fun onScreen(ctx: Context, screen: String) {
        when (screen) {
            "field" -> onField(ctx)
            "battle_command" -> onBattleCommand(ctx)
            "skill_list" -> {
                inBattle = true
                ctx.tap(Point(ctx.double("skill_x"), SKILL_ROW_Y))
                skillSet = !captured(ctx, SELECTED_LABEL)
                ctx.wait(0.8)
            }
            "battle" -> inBattle = true
            "battle_result" -> {
                inBattle = true
                ctx.tap(Point(0.5, 0.75))
                ctx.wait(0.8)
            }
            "title" -> {
                ctx.count("title_resumes")
                ctx.tap(Point(0.5, 0.75))
                ctx.wait(5.0)
            }
            "defeat" -> ctx.stop("party was defeated")
        }
    }

    private fun onField(ctx: Context) {
        if (inBattle) {
            inBattle = false
            skillSet = false
            lastView?.release(); lastView = null
            walked = false; stillWalks = 0
            ctx.count("battles")
            val limit = ctx.int("battles")
            if (limit > 0 && (ctx.stats["battles"] ?: 0) >= limit) ctx.stop("$limit battles done")
        }
        if (walked && !fieldMoved(ctx)) {
            stillWalks++
            val limit = ctx.int("stuck_walks")
            if (limit > 0 && stillWalks >= limit) ctx.stop("party seems stuck: $stillWalks walks without the view changing")
        } else {
            stillWalks = 0
        }
        walked = true
        val to = if (direction > 0) WALK_RIGHT else WALK_LEFT
        ctx.drag(WALK_FROM, to, ctx.int("walk_ms").toLong())
        direction = -direction
    }

    private fun onBattleCommand(ctx: Context) {
        inBattle = true
        if (ctx.str("mode") == "auto") {
            ctx.tap(if (ctx.find("battle_auto_button") != null) "battle_auto_button" else "battle_attack_button")
            ctx.wait(1.0)
            return
        }
        if (skillSet || (captured(ctx, SELECTED_LABEL) && ctx.find(SELECTED_LABEL) != null)) {
            ctx.count("turns")
            ctx.tap("battle_attack_button")
            ctx.wait(1.0)
        } else {
            val slot = ctx.int("slot").coerceIn(1, PORTRAIT_X.size) - 1
            ctx.tap(Point(PORTRAIT_X[slot], PORTRAIT_Y))
            ctx.wait(0.8)
        }
    }

    /** Did the last walk change the view (party/camera moved, not just idle animation)? */
    private fun fieldMoved(ctx: Context): Boolean {
        val g = ctx.frame?.gray ?: return true
        val roi = g.submat(Rect(0, (g.rows() * 0.25).toInt(), (g.cols() * 0.8).toInt(), (g.rows() * 0.55).toInt()))
        val small = Mat()
        Imgproc.resize(roi, small, Size(160.0, 60.0), 0.0, 0.0, Imgproc.INTER_AREA)
        roi.release()
        val prev = lastView
        lastView = small
        if (prev == null) return true
        val diff = Mat()
        Core.absdiff(small, prev, diff)
        val mean = Core.mean(diff).`val`[0]
        diff.release(); prev.release()
        return mean > STUCK_DIFF
    }

    private fun captured(ctx: Context, name: String) = ctx.vision.library.templates[name]?.available == true

    companion object {
        // walking is a virtual stick: press mid-screen and slide toward where to go
        val WALK_FROM = Point(0.5, 0.55)
        val WALK_RIGHT = Point(0.56, 0.55)
        val WALK_LEFT = Point(0.44, 0.55)
        const val SELECTED_LABEL = "verdanta_last_skill_selected"
        const val STUCK_DIFF = 4.0
        val PORTRAIT_X = listOf(0.161, 0.267, 0.373, 0.479)
        const val PORTRAIT_Y = 0.917
        const val SKILL_ROW_Y = 0.763
    }
}
