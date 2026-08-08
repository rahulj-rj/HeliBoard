// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Relative decode-latency signal for vocabulary-size changes (M3 raised the cap
 * from 10k to 50k words). JVM timings are not device timings, but the RATIO
 * between vocabulary sizes tracks how much extra trie the corridor pruning lets
 * through. Prints a small table; asserts only generous ceilings so CI noise
 * doesn't flake the build.
 *
 * The synthetic vocabulary is syllable-composed (not uniform-random letters) so
 * prefix sharing — what the trie walk actually traverses — looks like a real
 * language's.
 */
class DecodeLatencyBenchmarkTest {
    private val geometry = QwertyFixture.geometry
    private val scorers = listOf(KushlerScorer(), Shark2Scorer(), HybridScorer())
    private val decoder = GestureDecoder(HybridScorer())

    // words the timed decodes swipe; mix of lengths and regions of the keyboard
    private val targets = listOf(
        "the", "hello", "keyboard", "question", "something", "was",
        "information", "green", "coffee", "world", "press", "language",
    )

    @Test
    fun `decode latency at 10k and 50k words`() {
        val results = listOf(10_000, 50_000).map { size -> size to run(size) }
        for ((size, r) in results) {
            println("vocab=$size words: build=${r.buildMs} ms, " +
                    "decode avg=${"%.1f".format(r.avgMs)} ms, max=${"%.1f".format(r.maxMs)} ms " +
                    "(${r.decodes} decodes, 3 scorers each)")
        }
        val (small, large) = results.map { it.second }
        // generous ceilings — catch order-of-magnitude regressions, not jitter
        assertTrue(small.avgMs < 250, "10k avg decode ${small.avgMs} ms")
        assertTrue(large.avgMs < 500, "50k avg decode ${large.avgMs} ms")
        assertTrue(large.buildMs < 5_000, "50k trie build took ${large.buildMs} ms")
    }

    private class RunResult(val buildMs: Long, val avgMs: Double, val maxMs: Double, val decodes: Int)

    private fun run(vocabSize: Int): RunResult {
        val entries = syntheticEntries(vocabSize)
        val buildStart = System.nanoTime()
        val vocab = Vocabulary(entries)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000
        assertTrue(vocab.size >= vocabSize * 9 / 10, "built only ${vocab.size} of $vocabSize words")

        val paths = targets.flatMap { word ->
            listOf(SyntheticPathGenerator.idealPath(word, geometry)) +
                    (1L..3L).map { SyntheticPathGenerator.noisyPath(word, geometry, seed = it) }
        }
        // warmup (JIT) — not timed
        for (p in paths) decoder.decodeWithScorers(p, geometry, vocab, scorers, 10)

        var totalNs = 0L
        var maxNs = 0L
        var decodes = 0
        repeat(3) {
            for (p in paths) {
                val t = System.nanoTime()
                val out = decoder.decodeWithScorers(p, geometry, vocab, scorers, 10)
                val dt = System.nanoTime() - t
                totalNs += dt
                if (dt > maxNs) maxNs = dt
                decodes++
                assertTrue(out.isNotEmpty(), "no scorer produced results")
            }
        }
        return RunResult(buildMs, totalNs / 1e6 / decodes, maxNs / 1e6, decodes)
    }

    /**
     * [count] unique syllable-composed pseudo-words with Zipf-ish frequencies,
     * seeded (deterministic), PLUS the real [TestVocabulary] words so the timed
     * targets exist and pruning fights through plausible neighbors.
     */
    private fun syntheticEntries(count: Int): List<Pair<String, Int>> {
        val syllables = buildList {
            val onsets = listOf("b", "c", "d", "f", "g", "h", "j", "k", "l", "m",
                "n", "p", "r", "s", "t", "v", "w", "st", "tr", "ch", "sh", "pl", "gr", "")
            val nuclei = listOf("a", "e", "i", "o", "u", "ai", "ea", "ou", "oo")
            val codas = listOf("", "", "n", "r", "s", "t", "l", "ng", "ck")
            for (o in onsets) for (n in nuclei) for (c in codas) add(o + n + c)
        }
        val rng = Random(42)
        val words = LinkedHashSet<String>()
        for ((w, _) in TestVocabulary.entries) words.add(w.lowercase())
        while (words.size < count) {
            val n = 1 + rng.nextInt(4)
            val sb = StringBuilder()
            repeat(n) { sb.append(syllables[rng.nextInt(syllables.size)]) }
            if (sb.length in 2..20) words.add(sb.toString())
        }
        // Zipf-ish: rank-based decay onto the 1..255 dictionary scale
        return words.mapIndexed { i, w -> w to (255.0 / (1.0 + i * 254.0 / words.size)).toInt().coerceAtLeast(1) }
    }
}
