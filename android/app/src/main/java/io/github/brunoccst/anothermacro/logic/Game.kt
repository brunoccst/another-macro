package io.github.brunoccst.anothermacro.logic

import io.github.brunoccst.anothermacro.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * The game profile: `game.yaml` of the macro pack (the repo's game.yaml), with everything the
 * logic needs to know about the game it plays: its package and activity, and the words that mean
 * a purchase or premium currency. Code here asks [of] instead of naming the game, so the same
 * logic can serve another game with another profile (docs/game-profile.md).
 *
 * Without a pack (or with a broken file) the [DEFAULT] profile applies: the same values.
 */
object Game {
    const val FILE = "game.yaml"

    data class Profile(
        /** The game's name ("Another Eden"), also the name of its card in recent apps. */
        val name: String,
        /** The game's Android package. */
        val packageName: String,
        /** The game's own activity: the same package with another activity in front is a commercial. */
        val activity: String,
        /** Words that mean an offer to pay (lowercase). */
        val purchaseWords: List<String>,
        /** Words that mean premium currency (lowercase). */
        val premiumWords: List<String>,
    ) {
        /** Words that mean the game wants something bought: [purchaseWords] and [premiumWords]. */
        val buyWords: List<String> get() = purchaseWords + premiumWords
    }

    val DEFAULT = Profile(
        name = "Another Eden",
        packageName = "games.wfs.anothereden",
        activity = "net.wrightflyer.toybox.AppActivity",
        purchaseWords = listOf("purchase", "insufficient", "not enough", "remaining purchases", "buy "),
        premiumWords = listOf("chronos stone", "tsubura", "paid stone"),
    )

    @Volatile private var cached: Pair<String, Profile>? = null

    /** The profile of the macro pack in use ([DEFAULT] when it has none). */
    fun of(ctx: Context): Profile {
        val text = runCatching { ctx.assets.open(FILE).bufferedReader().use { it.readText() } }.getOrNull() ?: return DEFAULT
        cached?.let { (t, p) -> if (t == text) return p }
        return parse(text).also { cached = text to it }
    }

    /** The profile in [text] (game.yaml); missing values are [DEFAULT]'s. */
    fun parse(text: String): Profile {
        val y = runCatching { Load(LoadSettings.builder().build()).loadFromString(text) as? Map<*, *> }.getOrNull() ?: return DEFAULT
        val safety = y["safety"] as? Map<*, *> ?: emptyMap<Any, Any>()
        fun str(v: Any?, d: String) = v?.toString()?.takeIf { it.isNotBlank() } ?: d
        fun words(v: Any?, d: List<String>) = (v as? List<*>)?.map { it.toString().lowercase() }?.takeIf { it.isNotEmpty() } ?: d
        return Profile(
            name = str(y["name"], DEFAULT.name),
            packageName = str(y["package"], DEFAULT.packageName),
            activity = str(y["activity"], DEFAULT.activity),
            purchaseWords = words(safety["purchase_words"], DEFAULT.purchaseWords),
            premiumWords = words(safety["premium_words"], DEFAULT.premiumWords),
        )
    }
}
