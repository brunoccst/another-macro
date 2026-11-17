package macroforge.logic

import macroforge.Context
import macroforge.GameProfile

/**
 * The game profile for the logic: `game.yaml` of the macro pack when it has one (so a fix to the
 * profile reaches the phone with the pack), else the one the app was built with
 * ([GameProfile.current]). Code here asks [of] instead of naming the game (docs/game-profile.md).
 */
object Game {
    const val FILE = "game.yaml"

    @Volatile private var cached: Pair<String, GameProfile>? = null

    /** The profile of the macro pack in use, else the app's own. */
    fun of(ctx: Context): GameProfile {
        val text = runCatching { ctx.assets.open(FILE).bufferedReader().use { it.readText() } }.getOrNull() ?: return GameProfile.current
        cached?.let { (t, p) -> if (t == text) return p }
        val p = runCatching { GameProfile.parse(text) }.getOrNull()?.takeIf { it.packageName.isNotEmpty() } ?: return GameProfile.current
        cached = text to p
        return p
    }
}
