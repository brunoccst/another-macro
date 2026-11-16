package io.github.brunoccst.anothermacro

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The CI builds a dev build can follow, for the picker on System → Updates: main plus every
 * branch with an open pull request that has a `dev-<branch>` pre-release, and the branch the
 * phone follows now (even without a pull request), so it can always be seen.
 */
object DevBuilds {
    private const val REPO = "brunoccst/another-macro"

    /** One branch's newest CI build. [pr] and [title] come from its open pull request, if any. */
    data class Build(
        val branch: String, val version: String, val code: Long, val shell: String, val commit: String,
        val published: String, val pr: Int? = null, val title: String? = null,
    ) {
        /** The name shown in the picker. */
        val label get() = when {
            branch == "main" -> "main (default)"
            pr != null -> "PR #$pr" + (title?.let { ": $it" } ?: "")
            else -> branch
        }
    }

    /** What picking a build would do on this phone. */
    enum class Effect { IN_USE, LOGIC_ONLY, INSTALL, OLDER }

    /**
     * [IN_USE] when its logic is the one running and the app is at least as new; [LOGIC_ONLY] when it
     * fits this app (same shell), so only its logic pack loads; [INSTALL] when it is a newer app
     * build; [OLDER] when it needs another app build that is older than this one, which Android
     * can't install over it (the phone then follows the branch and installs its next, newer build).
     */
    fun effect(b: Build, shell: String, logicCommit: String, installedCode: Long): Effect = when {
        b.shell == shell && b.commit.isNotEmpty() && b.commit == logicCommit -> Effect.IN_USE
        b.shell == shell -> Effect.LOGIC_ONLY
        b.code > installedCode -> Effect.INSTALL
        else -> Effect.OLDER
    }

    /** A short line about [e] for the picker. */
    fun describe(e: Effect) = when (e) {
        Effect.IN_USE -> "in use"
        Effect.LOGIC_ONLY -> "loads without an install"
        Effect.INSTALL -> "needs an app install"
        Effect.OLDER -> "older app than this one: can't install it now, only follow it"
    }

    /** "Dev build: claude/x" -> "claude/x" (the tag lost the slashes). */
    fun branchOf(releaseName: String, tag: String): String =
        releaseName.removePrefix("Dev build:").trim().takeIf { releaseName.startsWith("Dev build:") && it.isNotEmpty() }
            ?: tag.removePrefix("dev-")

    /** A build from a pre-release's [name], [tag], notes ([body]) and publish time, or null if it isn't one. */
    fun parse(name: String, tag: String, body: String, published: String): Build? {
        if (!tag.startsWith("dev-")) return null
        val code = Regex("""versionCode:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val version = Regex("""versionName:\s*(\S+)""").find(body)?.groupValues?.get(1) ?: code.toString()
        val shell = Regex("""shell:\s*(\S+)""").find(body)?.groupValues?.get(1) ?: ""
        val commit = Regex("""commit:\s*([0-9a-f]+)""").find(body)?.groupValues?.get(1) ?: ""
        return Build(branchOf(name, tag), version, code, shell, commit, published)
    }

    /**
     * The builds to offer: main first, then the branches of [openPrs] (head branch -> number, title),
     * newest pull request first, then [followed] if it is none of those. A branch with no build is left out.
     */
    fun pick(builds: List<Build>, openPrs: Map<String, Pair<Int, String>>, followed: String): List<Build> {
        val byBranch = builds.groupBy { it.branch }.mapValues { (_, v) -> v.maxBy { it.code } }
        val out = mutableListOf<Build>()
        byBranch["main"]?.let { out += it }
        openPrs.entries.sortedByDescending { it.value.first }.forEach { (branch, pr) ->
            if (branch != "main") byBranch[branch]?.let { out += it.copy(pr = pr.first, title = pr.second) }
        }
        if (out.none { it.branch == followed }) byBranch[followed]?.let { out += it }
        return out
    }

    /** Fetch the builds to offer from GitHub. Blocking: call off the main thread. */
    fun fetch(followed: String): List<Build> {
        val releases = get("https://api.github.com/repos/$REPO/releases?per_page=100")
        val builds = (0 until releases.length()).mapNotNull {
            val r = releases.getJSONObject(it)
            if (!r.optBoolean("prerelease")) return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: JSONArray()
            if ((0 until assets.length()).none { i -> assets.getJSONObject(i).optString("name").endsWith(".apk") }) return@mapNotNull null
            parse(r.optString("name"), r.optString("tag_name"), r.optString("body"), r.optString("published_at"))
        }
        val pulls = get("https://api.github.com/repos/$REPO/pulls?state=open&per_page=100")
        val prs = (0 until pulls.length()).mapNotNull {
            val p = pulls.getJSONObject(it)
            val head = p.getJSONObject("head")
            // only branches of this repository have dev builds (forks don't publish)
            if (head.optJSONObject("repo")?.optString("full_name") != REPO) return@mapNotNull null
            head.getString("ref") to (p.getInt("number") to p.optString("title"))
        }.toMap()
        return pick(builds, prs, followed)
    }

    private fun get(url: String): JSONArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode == 403) throw IllegalStateException("GitHub's hourly limit is used up: try again later")
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
