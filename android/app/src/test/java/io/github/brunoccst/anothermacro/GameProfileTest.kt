package io.github.brunoccst.anothermacro

import io.github.brunoccst.anothermacro.logic.Game
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The game profile the logic reads (game.yaml in the macro pack). */
class GameProfileTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "game.yaml").isFile }

    @Test fun theRepoProfileIsTheDefault() {
        assertEquals(Game.DEFAULT, Game.parse(File(repo, "game.yaml").readText()))
    }

    @Test fun missingValuesTakeTheDefaults() {
        val p = Game.parse("name: Other Game\npackage: com.example.game\n")
        assertEquals("Other Game", p.name)
        assertEquals("com.example.game", p.packageName)
        assertEquals(Game.DEFAULT.activity, p.activity)
        assertEquals(Game.DEFAULT.purchaseWords, p.purchaseWords)
    }

    @Test fun brokenFileIsTheDefault() {
        assertEquals(Game.DEFAULT, Game.parse("{{not yaml"))
        assertEquals(Game.DEFAULT, Game.parse("- a list"))
    }

    @Test fun wordsAreLowercase() {
        assertEquals(listOf("gems"), Game.parse("safety: {premium_words: [Gems]}").premiumWords)
    }
}
