package io.github.brunoccst.anothermacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as tests/test_touch_recorder.py (the Python twin of TouchLog). */
class TouchLogTest {
    private val devices = """add device 1: /dev/input/event3
  name:     "sec_touchpad"
  events:
    ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
add device 2: /dev/input/event2
  name:     "sec_touchscreen"
  events:
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
"""

    private fun ev(t: Double, code: String, value: String, kind: String = "EV_ABS", dev: String? = "/dev/input/event2") =
        "[%14.6f] ".format(java.util.Locale.US, t) + (if (dev != null) "$dev: " else "") + "%-12s %-20s %s".format(kind, code, value)
    private fun syn(t: Double, dev: String? = "/dev/input/event2") = ev(t, "SYN_REPORT", "00000000", "EV_SYN", dev)
    private fun hex(v: Int) = "%08x".format(v)

    @Test fun touchscreenWinsOverTouchpad() {
        assertEquals("/dev/input/event2", TouchLog.parseDevices(devices)!!.path)
        val unnamed = devices.replace("sec_touchscreen", "panel")
        val log = listOf(ev(1.0, "ABS_MT_POSITION_X", "00000001"), syn(1.0)).joinToString("\n")
        assertEquals("/dev/input/event2", TouchLog.parseDevices(unnamed, log)!!.path)
    }

    @Test fun rotation() {
        assertEquals(0.0 to 1.0, TouchLog.toScreen(0.0, 0.0, 1))
        assertEquals(1.0 to 0.0, TouchLog.toScreen(0.0, 0.0, 3))
    }

    @Test fun holdAndTap() {
        val lines = listOf(
            ev(100.0, "ABS_MT_TRACKING_ID", "0000002a"), ev(100.0, "ABS_MT_POSITION_X", hex(540)),
            ev(100.0, "ABS_MT_POSITION_Y", hex(1200)), syn(100.0),
            ev(100.1, "ABS_MT_POSITION_Y", hex(1000)), syn(100.1),
            ev(100.9, "ABS_MT_POSITION_X", hex(541)), syn(100.9),
            ev(101.2, "ABS_MT_TRACKING_ID", "ffffffff"), syn(101.2),
            syn(102.0, "/dev/input/event4"),
            ev(103.0, "ABS_MT_TRACKING_ID", "0000002b"), ev(103.0, "ABS_MT_POSITION_X", hex(200)),
            ev(103.0, "ABS_MT_POSITION_Y", hex(2000)), syn(103.0),
            ev(103.06, "ABS_MT_TRACKING_ID", "ffffffff"), syn(103.06),
        ).joinToString("\n")
        val ts = TouchLog.parseDevices(devices, lines)!!
        val (hold, tap) = TouchLog.parseEvents(lines, ts, 1)
        assertEquals(100.0, hold.downS, 1e-9)
        assertEquals(1200L, hold.durMs)
        assertEquals(Math.round(1200.0 / 2399 * 10000) / 10000.0, hold.points[0].second, 1e-9)
        assertTrue(hold.points[1].second < hold.points[0].second)
        assertEquals(60L, tap.durMs)
    }

    @Test fun singleDeviceLinesWithoutPath() {
        val lines = listOf(
            ev(1.0, "ABS_MT_TRACKING_ID", "00000001", dev = null), ev(1.0, "ABS_MT_POSITION_X", hex(540), dev = null),
            ev(1.0, "ABS_MT_POSITION_Y", hex(1200), dev = null), syn(1.0, null),
            ev(1.05, "ABS_MT_TRACKING_ID", "ffffffff", dev = null), syn(1.05, null),
        ).joinToString("\n")
        val moves = TouchLog.parseEvents(lines, TouchLog.parseDevices(devices)!!, 1)
        assertEquals(1, moves.size)
        assertEquals(50L, moves[0].durMs)
    }

    @Test fun secondFingerMarksMultiTouch() {
        val lines = listOf(
            ev(5.0, "ABS_MT_TRACKING_ID", "00000001"), ev(5.0, "ABS_MT_POSITION_X", "00000010"),
            ev(5.0, "ABS_MT_POSITION_Y", "00000010"), syn(5.0),
            ev(5.1, "ABS_MT_SLOT", "00000001"), ev(5.1, "ABS_MT_TRACKING_ID", "00000002"),
            ev(5.1, "ABS_MT_POSITION_X", "00000100"), ev(5.1, "ABS_MT_POSITION_Y", "00000100"), syn(5.1),
            ev(5.2, "ABS_MT_TRACKING_ID", "ffffffff"), syn(5.2),
            ev(5.3, "ABS_MT_SLOT", "00000000"), ev(5.3, "ABS_MT_TRACKING_ID", "ffffffff"), syn(5.3),
        ).joinToString("\n")
        val (m) = TouchLog.parseEvents(lines, TouchLog.parseDevices(devices)!!, 0)
        assertTrue(m.multiTouch)
        assertEquals(300L, m.durMs)
    }
}
