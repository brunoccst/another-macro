package macroforge

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * The game this app plays, from `game.yaml` at the top of the repo (docs/game-profile.md). The
 * build bakes the file into the app ([BuildConfig.GAME_YAML]), so the app's own code never names
 * the game: it asks [current]. The logic pack may read a newer copy from the macro pack.
 */
data class GameProfile(
    /** The game's name, also the name of its card in recent apps. */
    val name: String,
    /** This app's name. */
    val appName: String,
    /** One line under the app's name. */
    val tagline: String,
    /** A few sentences on the Home page: what the app does. */
    val about: String,
    /** The game's Android package. */
    val packageName: String,
    /** The game's own activity: the same package with another activity in front is a commercial. */
    val activity: String,
    /** The GitHub repo ("owner/name") that hosts this app's code, builds and packs. */
    val repo: String,
    /** The game plugin: the Kotlin object (full name) with the game's own macro steps, or "" for none. */
    val plugin: String = "",
    /** Words that mean an offer to pay: never tapped (lowercase). */
    val purchaseWords: List<String>,
    /** Words that mean premium currency: taps there need the user's OK (lowercase). */
    val premiumWords: List<String>,
    /** The template that shows the game's shop is open (purchase guard), or "" for none. */
    val shopScreen: String,
    /** The template of the shop's close button, or "". */
    val shopClose: String,
    /** Templates a macro may tap while the shop is open (free offers, their dialogs, the close button). */
    val shopSafe: Set<String>,
    /** How the app groups macros (a macro's `group:`), in tab order. */
    val groups: List<Group>,
    /** What Claude is told about this game (the connector's instructions). */
    val claude: Claude,
    /** How run reports are shown (the outcome card, Statistics). */
    val reports: Reports = Reports("", emptyList()),
) {
    /** The run reports macros write: [title] of the Statistics section, [fields] in line order. */
    data class Reports(val title: String, val fields: List<ReportField>)

    /**
     * One field of a run report: [show] in a report's line ({value}; a switch shows it when on),
     * [total] in the totals (a switch counts, a number adds up, with [of] "x of y", a text counts
     * each value as {Value}), [card] also on the outcome card; [duration]: seconds shown as a time.
     */
    data class ReportField(val key: String, val show: String = "", val total: String = "", val of: String = "",
                           val card: String = "", val duration: Boolean = false)

    data class Group(val id: String, val title: String, val icon: String)

    data class Claude(
        /** Extra "How to work" lines about the game (how the party moves, which macros to use). */
        val howToWork: List<String>,
        /** Examples of premium currency, for the safety rule ("gems, paid coins"). */
        val premiumExamples: String,
        /** An installed macro that is a complete example of the format. */
        val exampleMacro: String,
        /** An example reward text for got: ("Gold x5"). */
        val rewardExample: String,
        /** Extra lines for the macro format: the game's own steps, built-in screens. */
        val format: List<String>,
    )

    /** Words that mean the game wants something bought: [purchaseWords] and [premiumWords]. */
    val buyWords: List<String> get() = purchaseWords + premiumWords

    /** The repo's web address. */
    val repoUrl: String get() = "https://github.com/$repo"

    companion object {
        /** The profile this app was built with. */
        val current: GameProfile by lazy { parse(BuildConfig.GAME_YAML) }

        /** The profile in [text] (game.yaml). Missing keys are empty, never another game's values. */
        fun parse(text: String): GameProfile {
            val y = runCatching { Load(LoadSettings.builder().build()).loadFromString(text) as? Map<*, *> }.getOrNull()
                ?: throw IllegalArgumentException("game.yaml: not a map of keys")
            fun map(v: Any?) = v as? Map<*, *> ?: emptyMap<Any, Any>()
            fun str(v: Any?) = v?.toString()?.trim().orEmpty()
            fun list(v: Any?) = (v as? List<*>)?.map { it.toString() }.orEmpty()
            val safety = map(y["safety"])
            val claude = map(y["claude"])
            return GameProfile(
                name = str(y["name"]),
                appName = str(y["app_name"]),
                tagline = str(y["tagline"]),
                about = str(y["about"]),
                packageName = str(y["package"]),
                activity = str(y["activity"]),
                repo = str(y["repo"]),
                plugin = str(y["plugin"]),
                purchaseWords = list(safety["purchase_words"]).map { it.lowercase() },
                premiumWords = list(safety["premium_words"]).map { it.lowercase() },
                shopScreen = str(safety["shop_screen"]),
                shopClose = str(safety["shop_close"]),
                shopSafe = list(safety["shop_safe"]).toSet(),
                groups = (y["groups"] as? List<*>).orEmpty().map { g -> map(g).let { Group(str(it["id"]), str(it["title"]), str(it["icon"])) } },
                claude = Claude(
                    howToWork = list(claude["how_to_work"]),
                    premiumExamples = str(claude["premium_examples"]),
                    exampleMacro = str(claude["example_macro"]),
                    rewardExample = str(claude["reward_example"]),
                    format = list(claude["format"]),
                ),
                reports = map(y["reports"]).let { r ->
                    Reports(str(r["title"]), (r["fields"] as? List<*>).orEmpty().map { f -> map(f).let {
                        ReportField(str(it["key"]), str(it["show"]), str(it["total"]), str(it["of"]), str(it["card"]), it["duration"] == true)
                    } })
                },
            )
        }
    }
}
