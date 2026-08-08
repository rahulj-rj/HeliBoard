// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the two Swype-authentic extras:
 * - caps excursion: mid-swipe detour above the keyboard capitalizes the letter
 *   swiped just before the detour (excursion at the start capitalizes the first letter)
 * - apostrophe via the period key: "I'm" is swiped i → '.' → m
 */
class ExcursionAndApostropheTest {
    private val geometry = QwertyFixture.geometry
    private val vocab = TestVocabulary.vocabulary
    private val preprocessor = GesturePreprocessor()
    private val scorers = listOf(KushlerScorer(), Shark2Scorer(), HybridScorer())

    private fun decode(scorer: Scorer, path: List<GesturePoint>) =
        GestureDecoder(scorer).decode(path, geometry, vocab, maxResults = 10)

    // ---- excursion preprocessing ----

    @Test
    fun `excursion points are stripped and arc recorded`() {
        val plain = SyntheticPathGenerator.idealPath("water", geometry)
        val idx = SyntheticPathGenerator.indexNearestToLetter(plain, geometry, "water", 2) // at 't'
        val withExc = SyntheticPathGenerator.withExcursion(plain, geometry, idx)
        val g = preprocessor.preprocess(withExc, geometry)
        assertEquals(1, g.excursionArcs.size, "expected exactly one excursion")
        assertTrue(g.points.all { it.y >= geometry.topEdge }, "all excursion points must be stripped")
        // stripping must not create fake near-180-degree inflections at the junction
        val plainG = preprocessor.preprocess(plain, geometry)
        assertTrue(g.inflections.size <= plainG.inflections.size + 1,
            "stripped path grew inflections: ${g.inflections.map { it.type }} vs ${plainG.inflections.map { it.type }}")
    }

    @Test
    fun `grazing just above the top row does not trigger an excursion`() {
        val plain = SyntheticPathGenerator.idealPath("water", geometry)
        val idx = SyntheticPathGenerator.indexNearestToLetter(plain, geometry, "water", 2)
        // only 0.2 key heights above the top edge -> below the 0.5 threshold
        val graze = SyntheticPathGenerator.withExcursion(plain, geometry, idx, heightKeyHeights = 0.2f)
        val g = preprocessor.preprocess(graze, geometry)
        assertTrue(g.excursionArcs.isEmpty(), "graze must not count as excursion")
    }

    // ---- excursion capitalization ----

    @Test
    fun `excursion at start capitalizes first letter`() {
        val plain = SyntheticPathGenerator.idealPath("hello", geometry)
        val path = SyntheticPathGenerator.withExcursion(plain, geometry, 0)
        for (scorer in scorers) {
            val top = decode(scorer, path).firstOrNull()?.word
            assertEquals("Hello", top, "${scorer.name}: got $top")
        }
    }

    @Test
    fun `mid-word excursion capitalizes the letter swiped before it`() {
        val plain = SyntheticPathGenerator.idealPath("water", geometry)
        val idx = SyntheticPathGenerator.indexNearestToLetter(plain, geometry, "water", 2) // right at 't'
        val path = SyntheticPathGenerator.withExcursion(plain, geometry, idx)
        for (scorer in scorers) {
            val top = decode(scorer, path).firstOrNull()?.word
            assertEquals("waTer", top, "${scorer.name}: got $top")
        }
    }

    @Test
    fun `double excursion capitalizes two letters`() {
        val plain = SyntheticPathGenerator.idealPath("water", geometry)
        val idxT = SyntheticPathGenerator.indexNearestToLetter(plain, geometry, "water", 2)
        var path = SyntheticPathGenerator.withExcursion(plain, geometry, idxT)
        // start excursion injected afterwards at index 0 (indices shift, but 0 stays 0)
        path = SyntheticPathGenerator.withExcursion(path, geometry, 0)
        for (scorer in scorers) {
            val top = decode(scorer, path).firstOrNull()?.word
            assertEquals("WaTer", top, "${scorer.name}: got $top")
        }
    }

    @Test
    fun `excursion paths keep decode accuracy of plain paths`() {
        val words = listOf("water", "hello", "people", "keyboard", "question", "something",
            "world", "think", "great", "house", "school", "letter", "green", "from", "place")
        for (scorer in scorers) {
            var plainCorrect = 0
            var excursionCorrect = 0
            for (word in words) {
                val plain = SyntheticPathGenerator.idealPath(word, geometry)
                if (decode(scorer, plain).firstOrNull()?.word.equals(word, ignoreCase = true)) plainCorrect++
                val exc = SyntheticPathGenerator.withExcursion(plain, geometry, 0)
                if (decode(scorer, exc).firstOrNull()?.word.equals(word, ignoreCase = true)) excursionCorrect++
            }
            assertTrue(excursionCorrect >= plainCorrect - 1,
                "${scorer.name}: excursion accuracy $excursionCorrect/${words.size} worse than plain $plainCorrect/${words.size}")
        }
    }

    // ---- apostrophe via period key ----

    @Test
    fun `sokgraph maps apostrophe to the period key`() {
        val sok = SokgraphBuilder.build("don't", geometry)!!
        assertEquals(5, sok.points.size)
        val period = geometry.key(KeyboardGeometry.PERIOD_KEY_CHAR)!!
        assertEquals(period.centerX, sok.points[3].x)
        assertEquals(period.centerY, sok.points[3].y)
    }

    @Test
    fun `contractions decode via the period key detour`() {
        for (word in listOf("I'm", "don't", "can't", "it's")) {
            val path = SyntheticPathGenerator.idealPath(word, geometry)
            for (scorer in scorers) {
                val results = decode(scorer, path)
                assertEquals(word.lowercase(), results.firstOrNull()?.word?.lowercase(),
                    "${scorer.name}/$word: got ${results.take(4).map { it.word }}")
            }
        }
    }

    @Test
    fun `straight i-m path does not surface I'm on top`() {
        // no dip to the period key -> the contraction must not win
        val path = SyntheticPathGenerator.idealPath("im", geometry) // i -> m straight
        for (scorer in scorers) {
            val top = decode(scorer, path).firstOrNull()?.word
            assertTrue(top?.lowercase() != "i'm", "${scorer.name}: straight i-m decoded as I'm")
        }
    }

    @Test
    fun `dipped i-m path ranks I'm first`() {
        val path = SyntheticPathGenerator.idealPath("i'm", geometry) // i -> period -> m
        for (scorer in scorers) {
            val top = decode(scorer, path).firstOrNull()?.word
            assertEquals("i'm", top?.lowercase(), "${scorer.name}: got $top")
        }
    }

    @Test
    fun `plain words unaffected by apostrophe support`() {
        for (word in listOf("in", "on", "hello", "water")) {
            val path = SyntheticPathGenerator.idealPath(word, geometry)
            assertEquals(word, decode(HybridScorer(), path).firstOrNull()?.word)
        }
    }
}
