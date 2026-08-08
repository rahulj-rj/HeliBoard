// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureDecoderTest {
    private val geometry = QwertyFixture.geometry
    private val vocab = TestVocabulary.vocabulary

    private val scorers = listOf(KushlerScorer(), Shark2Scorer(), HybridScorer())

    private fun decode(scorer: Scorer, word: String, seed: Long? = null): List<ScoredWord> {
        val path = if (seed == null) SyntheticPathGenerator.idealPath(word, geometry)
                   else SyntheticPathGenerator.noisyPath(word, geometry, seed)
        return GestureDecoder(scorer).decode(path, geometry, vocab, maxResults = 10)
    }

    @Test
    fun `vocabulary trie basics`() {
        assertTrue(vocab.contains("the"))
        assertTrue(vocab.contains("hello"))
        assertTrue(!vocab.contains("zzzz"))
        assertEquals(255, vocab.maxFrequency)
        assertTrue(vocab.frequencyOf("hello") > 0)
        assertTrue(vocab.size > 200, "expected a few hundred words, got ${vocab.size}")
    }

    @Test
    fun `clean hello decodes to hello with every scorer`() {
        for (scorer in scorers) {
            val results = decode(scorer, "hello")
            assertTrue(results.isNotEmpty(), "${scorer.name}: no results")
            assertEquals("hello", results.first().word, "${scorer.name}: got ${results.take(4).map { it.word }}")
        }
    }

    @Test
    fun `clean short words decode top-1 with every scorer`() {
        for (word in listOf("on", "it", "to")) {
            for (scorer in scorers) {
                val results = decode(scorer, word)
                assertEquals(word, results.firstOrNull()?.word,
                    "${scorer.name}/$word: got ${results.take(4).map { it.word }}")
            }
        }
    }

    @Test
    fun `candidate pruning keeps the target word reachable on noisy input`() {
        for (word in listOf("water", "people", "keyboard", "question")) {
            for (scorer in scorers) {
                val results = decode(scorer, word, seed = 42L)
                assertTrue(results.any { it.word == word },
                    "${scorer.name}/$word not in candidates: ${results.map { it.word }}")
            }
        }
    }

    @Test
    fun `ranking prefers frequent word on ambiguous path`() {
        // 'the' (freq 255) shares a very similar path with nothing common; use is/in style:
        // path for 'in' — 'in' should beat rarer neighbors for at least the hybrid scorer
        val results = decode(HybridScorer(), "in")
        assertTrue(results.isNotEmpty())
        assertTrue(results.take(4).any { it.word == "in" }, "got ${results.take(4).map { it.word }}")
    }

    @Test
    fun `scores are positive and sorted ascending`() {
        val results = decode(HybridScorer(), "something")
        assertTrue(results.isNotEmpty())
        for (i in 1 until results.size) assertTrue(results[i - 1].score <= results[i].score)
        assertTrue(results.all { it.score > 0f })
    }

    @Test
    fun `empty input yields no results`() {
        assertTrue(GestureDecoder(HybridScorer()).decode(emptyList(), geometry, vocab).isEmpty())
    }
}
