// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scorer-vs-scorer accuracy harness (spec M1: "from day one").
 * Runs all three scorers on the same synthetic corpus and reports top-1 / top-4
 * accuracy (Swype showed 4 candidates), overall and per category, clean vs noisy.
 */
class AccuracyHarnessTest {

    private val geometry = QwertyFixture.geometry
    private val vocab = TestVocabulary.vocabulary

    // Categories of tricky cases, all present in TestVocabulary.
    private val categories: Map<String, List<String>> = mapOf(
        "short" to listOf("is", "in", "on", "it", "at", "as", "an", "to", "of", "or", "up", "go", "no", "my"),
        "double" to listOf("loop", "ball", "been", "seen", "good", "look", "book", "school", "less", "all",
            "will", "still", "keep", "too", "see", "free", "feel", "week", "off", "happy",
            "sorry", "little", "letter", "better", "coffee", "summer"),
        "long" to listOf("something", "important", "information", "government", "understand", "keyboard",
            "question", "probably", "beautiful", "computer", "remember", "together", "children",
            "business", "education", "experience", "technology", "everything", "university", "different"),
        "adjacent" to listOf("then", "than", "form", "from", "were", "wore", "hot", "hit", "top", "tip",
            "cat", "car", "pen", "pin", "pan", "sat", "sit", "set", "hear", "heat", "not", "nor"),
        "normal" to listOf("hello", "world", "water", "people", "house", "place", "think", "great",
            "right", "point", "story", "mother", "answer"),
    )

    private val noisySeeds = listOf(1L, 2L, 3L)

    private class Tally {
        var runs = 0
        var top1 = 0
        var top4 = 0
        fun add(rank: Int) { // rank: 0-based position of target, -1 if absent
            runs++
            if (rank == 0) top1++
            if (rank in 0..3) top4++
        }
        val top1Pct get() = if (runs == 0) 0.0 else 100.0 * top1 / runs
        val top4Pct get() = if (runs == 0) 0.0 else 100.0 * top4 / runs
    }

    private class ScorerResult(val name: String) {
        val clean = Tally()
        val noisy = Tally()
        val cleanByCategory = HashMap<String, Tally>()
        val noisyByCategory = HashMap<String, Tally>()
    }

    private fun runHarness(): List<ScorerResult> {
        val scorers: List<Scorer> = listOf(KushlerScorer(), Shark2Scorer(), HybridScorer())
        val results = scorers.map { ScorerResult(it.name) }
        for ((si, scorer) in scorers.withIndex()) {
            val decoder = GestureDecoder(scorer)
            val res = results[si]
            for ((category, words) in categories) {
                for (word in words) {
                    // clean
                    val cleanPath = SyntheticPathGenerator.idealPath(word, geometry)
                    val cleanRank = rankOf(decoder.decode(cleanPath, geometry, vocab, 10), word)
                    res.clean.add(cleanRank)
                    res.cleanByCategory.getOrPut(category) { Tally() }.add(cleanRank)
                    // noisy
                    for (seed in noisySeeds) {
                        val noisyPath = SyntheticPathGenerator.noisyPath(word, geometry, seed)
                        val noisyRank = rankOf(decoder.decode(noisyPath, geometry, vocab, 10), word)
                        res.noisy.add(noisyRank)
                        res.noisyByCategory.getOrPut(category) { Tally() }.add(noisyRank)
                    }
                }
            }
        }
        return results
    }

    private fun rankOf(results: List<ScoredWord>, word: String): Int =
        results.indexOfFirst { it.word == word }

    @Test
    fun `accuracy harness - all three scorers`() {
        val results = runHarness()

        val sb = StringBuilder("\n=== Gesture decoder accuracy harness ===\n")
        for (r in results) {
            sb.append(String.format(
                "%-8s clean: top1 %5.1f%% top4 %5.1f%% (n=%d) | noisy: top1 %5.1f%% top4 %5.1f%% (n=%d)\n",
                r.name, r.clean.top1Pct, r.clean.top4Pct, r.clean.runs,
                r.noisy.top1Pct, r.noisy.top4Pct, r.noisy.runs))
            for (category in categories.keys) {
                val c = r.cleanByCategory[category]!!
                val n = r.noisyByCategory[category]!!
                sb.append(String.format(
                    "    %-10s clean top1 %5.1f%% top4 %5.1f%% | noisy top1 %5.1f%% top4 %5.1f%%\n",
                    category, c.top1Pct, c.top4Pct, n.top1Pct, n.top4Pct))
            }
        }
        println(sb)

        // Floors chosen after observing actual numbers (asserted slightly below achieved
        // values so the tests stay stable against small tuning changes). Achieved 2026-08-08:
        //   kushler: clean 91.6/100.0, noisy 80.0/99.6
        //   shark2:  clean 95.8/100.0, noisy 94.0/100.0
        //   hybrid:  clean 94.7/100.0, noisy 94.0/100.0
        for (r in results) {
            assertTrue(r.clean.top1Pct >= 88.0, "${r.name} clean top1 ${r.clean.top1Pct}")
            assertTrue(r.clean.top4Pct >= 98.0, "${r.name} clean top4 ${r.clean.top4Pct}")
            assertTrue(r.noisy.top1Pct >= 72.0, "${r.name} noisy top1 ${r.noisy.top1Pct}")
            assertTrue(r.noisy.top4Pct >= 95.0, "${r.name} noisy top4 ${r.noisy.top4Pct}")
        }
    }
}
