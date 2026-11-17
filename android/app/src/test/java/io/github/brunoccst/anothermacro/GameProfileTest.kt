package io.github.brunoccst.anothermacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The game profile (game.yaml), baked into the app and read from the macro pack. */
class GameProfileTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "game.yaml").isFile }

    @Test fun theAppIsBuiltWithTheRepoProfile() {
        assertEquals(GameProfile.parse(File(repo, "game.yaml").readText()), GameProfile.current)
    }

    @Test fun theRepoProfileIsComplete() {
        val p = GameProfile.current
        for (v in listOf(p.name, p.appName, p.tagline, p.about, p.packageName, p.activity, p.repo)) assertTrue(v.isNotEmpty())
        assertTrue(p.purchaseWords.isNotEmpty() && p.premiumWords.isNotEmpty())
        assertTrue(p.groups.any { it.id == "custom" } && p.groups.any { it.id == "other" })
    }

    @Test fun missingValuesAreEmpty() {
        val p = GameProfile.parse("name: Other Game\npackage: com.example.game\n")
        assertEquals("Other Game", p.name)
        assertEquals("com.example.game", p.packageName)
        assertEquals("", p.activity)
        assertTrue(p.purchaseWords.isEmpty() && p.groups.isEmpty())
    }

    @Test fun wordsAreLowercase() {
        assertEquals(listOf("gems"), GameProfile.parse("safety: {premium_words: [Gems]}").premiumWords)
    }

    @Test fun groupsComeFromTheProfile() {
        assertEquals(GameProfile.current.groups.map { it.id }, Groups.ALL.map { it.id })
    }

    @Test fun claudeIsToldAboutTheProfilesGame() {
        val p = GameProfile.parse(File(repo, "game.yaml").readText().replace(GameProfile.current.name, "Some Game"))
        val text = ClaudePrompt.connector(1024, 461, p)
        assertTrue(text.startsWith("These tools control Some Game, a mobile game"))
        assertTrue(p.claude.howToWork.all { "- $it" in text })
    }
}
