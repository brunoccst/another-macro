package anothermacro

import macroforge.*

import macroforge.DevBuilds.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The build picker on System → Updates. */
class DevBuildsTest {
    private fun notes(code: Long, shell: String = "s1", commit: String = "abc1234") =
        "Dev build of `x` at abc1234 (not a release).\nversionName: 0.0.$code-dev\nversionCode: $code\nshell: $shell\ncommit: $commit"

    private fun build(branch: String, code: Long, shell: String = "s1", commit: String = "c$code") =
        DevBuilds.Build(branch, "0.0.$code-dev", code, shell, commit, "2026-11-16T09:00:00Z")

    @Test fun parsesThePreReleaseNotes() {
        val b = DevBuilds.parse("Dev build: claude/project-thread-x", "dev-claude-project-thread-x", notes(500, "deadbeef", "0123abcd"), "t")!!
        assertEquals("claude/project-thread-x", b.branch)
        assertEquals("0.0.500-dev", b.version)
        assertEquals(500L, b.code)
        assertEquals("deadbeef", b.shell)
        assertEquals("0123abcd", b.commit)
    }

    @Test fun skipsOtherReleases() {
        assertNull(DevBuilds.parse("Macros: main", "macros-main", notes(1), "t"))
        assertNull(DevBuilds.parse("Dev build: main", "dev-main", "no numbers here", "t"))
    }

    @Test fun branchFallsBackToTheTag() {
        assertEquals("main", DevBuilds.branchOf("something else", "dev-main"))
    }

    @Test fun mainFirstThenOpenPullRequestsNewestFirst() {
        val builds = listOf(build("old/merged", 1), build("a", 5), build("main", 3), build("b", 4))
        val prs = mapOf("a" to (10 to "First"), "b" to (12 to "Second"), "no-build" to (13 to "Nothing"))
        val picked = DevBuilds.pick(builds, prs, followed = "main")
        assertEquals(listOf("main", "b", "a"), picked.map { it.branch })
        assertEquals("PR #12: Second", picked[1].label)
        assertEquals("main (default)", picked[0].label)
    }

    @Test fun theFollowedBranchIsShownEvenWithoutAPullRequest() {
        val picked = DevBuilds.pick(listOf(build("main", 3), build("old/merged", 9)), emptyMap(), followed = "old/merged")
        assertEquals(listOf("main", "old/merged"), picked.map { it.branch })
    }

    @Test fun whatPickingDoes() {
        assertEquals(Effect.IN_USE, DevBuilds.effect(build("a", 5, "s1", "c5"), "s1", "c5", 5))
        assertEquals(Effect.LOGIC_ONLY, DevBuilds.effect(build("a", 2, "s1", "c2"), "s1", "c5", 5))
        assertEquals(Effect.INSTALL, DevBuilds.effect(build("a", 9, "s2"), "s1", "c5", 5))
        assertEquals(Effect.OLDER, DevBuilds.effect(build("main", 3, "s2"), "s1", "c5", 5))
    }
}
