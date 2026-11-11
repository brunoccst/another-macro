package io.github.brunoccst.anothermacro

import org.junit.Assert.assertTrue
import org.junit.Test

/** What the connector tells Claude about an all-black screen capture (no device). */
class ScreenWakeTest {
    @Test fun blackReasonNamesTheCause() {
        assertTrue(ScreenWake.blackReason(true, null).contains("screen capture is off"))
        assertTrue(ScreenWake.blackReason(false, 500).contains("screen is off"))
        assertTrue(ScreenWake.blackReason(true, 3_000).contains("capture may have stopped"))
    }

    @Test fun describeShowsScreenAndCapture() {
        assertTrue(ScreenWake.describe(false, 2_000, true).let { it.contains("screen OFF") && it.contains("ALL BLACK") })
        assertTrue(ScreenWake.describe(true, null, null).contains("capture OFF"))
        assertTrue(ScreenWake.describe(true, 1_000, false).let { it.startsWith("screen on") && !it.contains("BLACK") })
    }
}
