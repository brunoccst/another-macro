package io.github.brunoccst.anothermacro

const val GAME_PACKAGE = "games.wfs.anothereden"

/**
 * Port of start-game: launch the game (or bring it to the front), tap Continue,
 * and on the first login of the day always take the daily ad (premium: close the
 * thank-you instead), confirm the reward, stop on the field.
 * With watch_ad=false the ad screens aren't looked for: it stops once past the title.
 */
class StartGame : Scenario() {
    override val name = "start-game"
    override val description = "Turn the game on, always watch the daily login-bonus ad, stop on the field."
    override val templateFolders = listOf("start_game")
    override val params = linkedMapOf(
        "watch_ad" to Param("true", "take the daily login-bonus ad; false = just open the game"),
        "ad_wait_s" to Param("45", "how long to let an ad play before pressing back to close it"),
    )
    override val screens get() = if (watchAd) listOf("ad_reward", "premium_thanks", "ad_offer", "field", "title") else listOf("field", "title")
    override val unknownTimeoutS = 180.0

    private var watchAd = true
    private var watchingSince: Long? = null
    private var continued = false

    override fun setup(ctx: Context) {
        watchAd = ctx.bool("watch_ad")
        ctx.launchApp(GAME_PACKAGE)
        ctx.count("launches")
        ctx.wait(3.0)
    }

    override fun onScreen(ctx: Context, screen: String) {
        when (screen) {
            "title" -> if (ctx.find("title_continue") != null) {
                ctx.count("continue_taps")
                continued = true
                ctx.tap(Point(0.5, 0.75))
                ctx.wait(8.0) // the title stays up while the game logs in
            } else ctx.wait(2.0)
            "ad_offer" -> {
                ctx.count("ads_accepted")
                ctx.tap("watch_video_button")
                watchingSince = System.currentTimeMillis()
                ctx.wait(3.0)
            }
            "premium_thanks" -> {
                ctx.count("premium_skips")
                ctx.tap("dialog_close_button")
                watchingSince = null
                ctx.wait(2.0)
            }
            "ad_reward" -> {
                ctx.count("ad_rewards")
                ctx.tap("dialog_ok_button")
                watchingSince = null
                ctx.wait(2.0)
            }
            "field" -> ctx.stop("the game is on and on the field")
        }
    }

    override fun onUnknown(ctx: Context) {
        if (!watchAd) {
            if (continued && ctx.frame?.portraitAppInFront != true) ctx.stop("the game is open (daily ad not checked)")
            ctx.wait(2.0)
            return
        }
        val since = watchingSince
        if (since != null) {
            // an ad is playing: let it finish, then back closes it; never tap inside it
            if (System.currentTimeMillis() - since > ctx.int("ad_wait_s") * 1000L) {
                ctx.back()
                ctx.count("ad_backs")
                watchingSince = System.currentTimeMillis() - ctx.int("ad_wait_s") * 1000L + 10_000
            }
            ctx.wait(2.0)
            return
        }
        if (ctx.frame?.portraitAppInFront == true) {
            ctx.launchApp(GAME_PACKAGE) // something else came to the front: bring the game back
            ctx.wait(3.0)
            return
        }
        // loading screens and the cat delivering the login bonus: tap the empty upper middle
        ctx.tap(Point(0.5, 0.3))
        ctx.wait(1.5)
    }
}

/**
 * Port of stop-game: back on the field opens "Close the app?", Yes closes the
 * game. From other screens it keeps pressing back until the dialog shows.
 * (Apps can't force-stop other apps without root, so there is no fallback.)
 */
class StopGame : Scenario() {
    override val name = "stop-game"
    override val description = "Turn the game off (back → 'Close the app?' → Yes)."
    override val screens = listOf("close_app", "field")
    override val unknownTimeoutS = 60.0
    private var confirmed = false
    private var backs = 0

    override fun onScreen(ctx: Context, screen: String) {
        when (screen) {
            "close_app" -> {
                ctx.count("confirms")
                ctx.tap(Point(0.588, 0.568))
                confirmed = true
                ctx.wait(3.0)
            }
            "field" -> { ctx.back(); ctx.wait(1.5) }
        }
    }

    override fun onUnknown(ctx: Context) {
        // the game is landscape-only: a portrait app in front means it's gone (or was never up)
        if (ctx.frame?.portraitAppInFront == true) ctx.stop(if (confirmed) "the game is off" else "the game is not in front")
        if (++backs > 12) ctx.stop("could not reach the field to close the game")
        ctx.back()
        ctx.wait(1.5)
    }
}
