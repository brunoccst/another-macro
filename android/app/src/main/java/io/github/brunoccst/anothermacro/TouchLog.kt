package io.github.brunoccst.anothermacro

/**
 * `getevent` output into moves: the same as touch_recorder.py (keep the two in step; both
 * have tests with the same cases). Pure Kotlin so it runs in JVM unit tests.
 *
 *  - [parseDevices]: the touchscreen from `getevent -pl` (named *touchscreen*, else the device
 *    with the most events, else the first with multi-touch positions; Samsung also has a
 *    "sec_touchpad" with the same axes).
 *  - [parseEvents]: `getevent -lt` lines (with or without the device path) into the moves of the
 *    first finger down, in fractions of the screen as shown for a Display [rotation].
 */
object TouchLog {
    data class Touchscreen(val path: String, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val name: String = "")

    /** One move: [downS] is the kernel's timestamp of the press; [points] are (ms since the press, x, y). */
    data class Move(val downS: Double, val points: List<Triple<Long, Double, Double>>, val multiTouch: Boolean) {
        val durMs: Long get() = points.last().first
    }

    private val LINE = Regex("""^\[\s*(\d+\.\d+)\]\s+(?:(/dev/input/\S+):\s+)?(\S+)\s+(\S+)\s+(\S+)""")
    private val DEVICE = Regex("""add device \d+:\s+(/dev/input/\S+)""")
    private val NAME = Regex("""name:\s+"([^"]*)"""")
    private val AXIS = Regex("""(ABS_MT_POSITION_[XY])\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
    private const val UP = 0xFFFFFFFFL

    fun parseDevices(text: String, log: String = ""): Touchscreen? {
        val found = text.split(Regex("(?=add device \\d+:)")).mapNotNull { b ->
            val m = DEVICE.find(b) ?: return@mapNotNull null
            val axes = AXIS.findAll(b).associate { it.groupValues[1] to (it.groupValues[2].toInt() to it.groupValues[3].toInt()) }
            val x = axes["ABS_MT_POSITION_X"] ?: return@mapNotNull null
            val y = axes["ABS_MT_POSITION_Y"] ?: return@mapNotNull null
            Touchscreen(m.groupValues[1], x.first, x.second, y.first, y.second, NAME.find(b)?.groupValues?.get(1) ?: "")
        }
        if (found.isEmpty()) return null
        found.firstOrNull { "touchscreen" in it.name.lowercase() }?.let { return it }
        val counts = found.associate { it.path to Regex.escape(it.path + ":").toRegex().findAll(log).count() }
        return if (counts.values.any { it > 0 }) found.maxByOrNull { counts.getValue(it.path) } else found.first()
    }

    /** Natural-orientation fractions to fractions of the screen as shown (1 = ROTATION_90, the usual landscape). */
    fun toScreen(rx: Double, ry: Double, rotation: Int): Pair<Double, Double> = when (rotation) {
        1 -> ry to 1 - rx
        2 -> 1 - rx to 1 - ry
        3 -> 1 - ry to rx
        else -> rx to ry
    }

    private class Slot { var tracking = -1L; var x: Int? = null; var y: Int? = null }

    fun parseEvents(log: String, ts: Touchscreen, rotation: Int): List<Move> {
        val slots = HashMap<Int, Slot>()
        var slot = 0
        var primary: Int? = null
        var downS = 0.0
        var multi = false
        var pts = ArrayList<Triple<Double, Double, Double>>() // (s, x, y)
        val out = ArrayList<Move>()
        val spanX = maxOf(1, ts.maxX - ts.minX).toDouble()
        val spanY = maxOf(1, ts.maxY - ts.minY).toDouble()
        for (raw in log.lineSequence()) {
            val m = LINE.find(raw.trim()) ?: continue
            val dev = m.groupValues[2]
            if (dev.isNotEmpty() && dev != ts.path) continue
            val t = m.groupValues[1].toDouble()
            val kind = m.groupValues[3]; val code = m.groupValues[4]; val value = m.groupValues[5]
            if (kind == "EV_ABS") {
                val v = value.toLongOrNull(16) ?: continue
                when (code) {
                    "ABS_MT_SLOT" -> slot = v.toInt()
                    "ABS_MT_TRACKING_ID" -> {
                        val s = slots.getOrPut(slot) { Slot() }
                        if (v == UP) s.tracking = -1 else {
                            s.tracking = v
                            if (primary == null) { primary = slot; downS = t; multi = false; pts = ArrayList() }
                            else if (slot != primary) multi = true
                        }
                    }
                    "ABS_MT_POSITION_X" -> slots.getOrPut(slot) { Slot() }.x = v.toInt()
                    "ABS_MT_POSITION_Y" -> slots.getOrPut(slot) { Slot() }.y = v.toInt()
                }
            } else if (kind == "EV_SYN" && code == "SYN_REPORT" && primary != null) {
                val s = slots[primary] ?: continue
                val sx = s.x; val sy = s.y
                if (sx != null && sy != null && (s.tracking >= 0 || pts.isEmpty())) {
                    val fx = ((sx - ts.minX) / spanX).coerceIn(0.0, 1.0)
                    val fy = ((sy - ts.minY) / spanY).coerceIn(0.0, 1.0)
                    val (x, y) = toScreen(fx, fy, rotation)
                    pts += Triple(t, x, y)
                }
                if (s.tracking < 0) {
                    if (pts.isNotEmpty()) {
                        pts += Triple(t, pts.last().second, pts.last().third)
                        out += move(downS, pts, multi)
                    }
                    primary = null
                }
            }
        }
        return out
    }

    private fun r4(v: Double) = Math.round(v * 10000) / 10000.0

    private fun move(downS: Double, raw: List<Triple<Double, Double, Double>>, multi: Boolean): Move {
        val t0 = raw.first().first
        val pts = ArrayList<Triple<Long, Double, Double>>()
        for ((t, x, y) in raw) {
            val p = Triple(Math.round((t - t0) * 1000), r4(x), r4(y))
            // at most one point per frame, always the last one
            if (pts.size > 1 && p.first - pts.last().first < 16) pts[pts.size - 1] = p
            else if (pts.isEmpty() || p != pts.last()) pts += p
        }
        if (pts.size == 1) pts += Triple(Math.round((raw.last().first - t0) * 1000), pts[0].second, pts[0].third)
        return Move(Math.round(downS * 10000) / 10000.0, pts, multi)
    }
}
