// SPDX-License-Identifier: GPL-3.0-only
// Shared decode pipeline: capture → preprocess → prune → score → rank.
// Pruning per spec: start/end key neighborhoods, sokgraph path-length ratio band,
// minimum letters from inflection count, trie walk restricted to key-neighborhood
// transitions. Ranking per the patent formula (lower is better).
package helium314.keyboard.gesture

import kotlin.math.ln

class DecoderConfig(
    /** First/last letter must be within this many key widths of PEN_DOWN / PEN_UP. */
    val endpointRadiusKeyWidths: Float = 1.6f,
    /** Mid letters must be within this many key widths of the drawn path (trie-walk corridor). */
    val corridorRadiusKeyWidths: Float = 1.4f,
    /** Allowed backtrack (key widths) when requiring letters to progress along the path. */
    val progressSlackKeyWidths: Float = 1.2f,
    /** Sokgraph length must be within [1/ratio, ratio] of drawn length (with additive slack). */
    val lengthRatioBand: Float = 2.0f,
    /** Additive slack (key widths) for the length band — dominates for short words. */
    val lengthSlackKeyWidths: Float = 2.0f,
    /** Extra drawn length allowed per double letter (the loop gesture adds arc). */
    val doubleLetterArcKeyWidths: Float = 1.5f,
    /** Inflections at or above this confidence demand a letter (min-letter pruning). */
    val strongInflectionConfidence: Float = 0.8f,
    /** Slack subtracted from the strong-inflection count for min-letter pruning. */
    val minLetterSlack: Int = 1,
    /** Hard cap on candidates sent to the scorer (safety net for huge vocabularies). */
    val maxCandidates: Int = 512,
)

class GestureDecoder(
    private val scorer: Scorer,
    private val config: DecoderConfig = DecoderConfig(),
    private val preprocessor: GesturePreprocessor = GesturePreprocessor(),
) {

    fun decode(
        points: List<GesturePoint>,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
        maxResults: Int = 10,
    ): List<ScoredWord> =
        decodeWithScorers(points, geometry, vocabulary, listOf(scorer), maxResults)[scorer.name] ?: emptyList()

    /**
     * Runs the shared pipeline (preprocess + prune) ONCE and scores the same candidate
     * set with each of [scorers] — scoring is the cheap stage, so comparing scorers per
     * swipe costs little. Returns results keyed by [Scorer.name], each ranked ascending.
     */
    fun decodeWithScorers(
        points: List<GesturePoint>,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
        scorers: List<Scorer>,
        maxResults: Int = 10,
    ): Map<String, List<ScoredWord>> {
        val out = LinkedHashMap<String, List<ScoredWord>>()
        if (points.isEmpty()) return out
        val gesture = preprocessor.preprocess(points, geometry)
        if (gesture.points.isEmpty()) return out

        val candidates = collectCandidates(gesture, geometry, vocabulary)
        if (candidates.isEmpty()) return out

        // caps-excursion: uppercase the letter matched nearest before each excursion.
        // Applied once per candidate, shared by all scorers (post-scoring display form).
        val displayWords = candidates.map { applyExcursionCaps(it, gesture, geometry) }

        val maxFreq = vocabulary.maxFrequency.toFloat()
        for (s in scorers) {
            val scored = ArrayList<ScoredWord>(candidates.size)
            for ((i, candidate) in candidates.withIndex()) {
                val raw = s.score(gesture, candidate.sokgraph, geometry)
                if (raw == Float.MAX_VALUE) continue
                // patent ranking formula: score * (log(MAX_FREQ / word_frequency) + 1), lower is better
                val rank = raw * (ln(maxFreq / candidate.frequency) + 1f)
                scored.add(ScoredWord(displayWords[i], rank, raw, candidate.frequency))
            }
            scored.sortBy { it.score }
            out[s.name] = if (scored.size > maxResults) scored.subList(0, maxResults) else scored
        }
        return out
    }

    /**
     * For each excursion arc position, capitalize the candidate letter whose matched
     * path position most closely PRECEDES the excursion exit. An excursion before any
     * letter's match position capitalizes the first letter (the proper-noun case).
     */
    private fun applyExcursionCaps(candidate: Candidate, gesture: PreprocessedGesture, geometry: KeyboardGeometry): String {
        val excursions = gesture.excursionArcs
        val word = candidate.sokgraph.word
        if (excursions.isEmpty() || candidate.letterArcs.isEmpty()) return word
        // stripping the excursion stub shifts the capped letter's match slightly PAST the
        // junction, so allow ~0.6 key widths of forward slack (still < one key transition)
        val slack = 0.6f * geometry.keyWidth
        val chars = word.toCharArray()
        for (e in excursions) {
            var idx = 0
            for (i in candidate.letterArcs.indices) {
                if (candidate.letterArcs[i] <= e + slack) idx = i
            }
            while (idx > 0 && !chars[idx].isLetter()) idx-- // never "capitalize" an apostrophe
            chars[idx] = chars[idx].uppercaseChar()
        }
        return String(chars)
    }

    // ---- candidate generation (pruning, cheap → expensive) ----

    private class Candidate(
        val sokgraph: Sokgraph,
        val frequency: Int,
        /** matched arc position along the drawn path per word character (trie-walk order) */
        val letterArcs: FloatArray,
    )

    private fun collectCandidates(
        gesture: PreprocessedGesture,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
    ): List<Candidate> {
        val kw = geometry.keyWidth
        val start = gesture.points.first()
        val end = gesture.points.last()
        val endpointRadius = config.endpointRadiusKeyWidths * kw
        val corridorRadius = config.corridorRadiusKeyWidths * kw
        val progressSlack = config.progressSlackKeyWidths * kw
        val drawnLength = gesture.pathLength
        val minLetters = (gesture.strongInflectionCount(config.strongInflectionConfidence)
                - config.minLetterSlack).coerceAtLeast(1)

        // prune 1: first letter within neighborhood of PEN_DOWN
        val startChars = geometry.keysNear(start.x, start.y, endpointRadius).map { it.char }.toHashSet()
        // last-letter neighborhood, checked when a word node is reached
        val endChars = geometry.keysNear(end.x, end.y, endpointRadius).map { it.char }.toHashSet()

        val out = ArrayList<Candidate>()

        // cache per character: (distance to path, arc position) starting from a given arc — memoized coarsely
        // (apostrophes map to the period key via keyForWordChar, so they cache separately)
        val distCache = HashMap<Char, Float>()

        fun distToPath(c: Char): Float = distCache.getOrPut(c) {
            val k = geometry.keyForWordChar(c) ?: return@getOrPut Float.MAX_VALUE
            gesture.distanceToPath(k.centerX, k.centerY)
        }

        // matched arc position per character on the current trie path (for excursion caps)
        val arcStack = ArrayList<Float>()

        // prune 4: trie walk restricted to key-neighborhood transitions,
        // with letters required to progress (with slack) along the path
        fun walk(node: Vocabulary.Node, c: Char, arcPos: Float, depth: Int) {
            if (out.size >= config.maxCandidates) return
            val key = geometry.keyForWordChar(c) ?: return
            // corridor check: key must be near the drawn path at all
            if (distToPath(c) > corridorRadius) return
            // ordering check: key must be reachable at/after the previous letter's position
            val (d, newArc) = gesture.nearestArcPositionFrom(
                key.centerX, key.centerY, (arcPos - progressSlack).coerceAtLeast(0f), corridorRadius
            )
            if (d > corridorRadius) return

            arcStack.add(newArc)
            val word = node.word
            if (word != null && word.length >= minLetters && key.char in endChars) {
                // prune 2: sokgraph length within ratio band of drawn length
                val sok = SokgraphBuilder.build(word, geometry)
                if (sok != null && lengthBandOk(sok, drawnLength, kw)) {
                    out.add(Candidate(sok, node.frequency, arcStack.toFloatArray()))
                }
            }
            for ((childChar, childNode) in node.children) {
                walk(childNode, childChar, newArc, depth + 1)
            }
            arcStack.removeAt(arcStack.size - 1)
        }

        for ((c, childNode) in vocabulary.root.children) {
            val key = geometry.keyForWordChar(c) ?: continue
            if (key.char in startChars) walk(childNode, c, 0f, 1)
        }
        return out
    }

    private fun lengthBandOk(sok: Sokgraph, drawnLength: Float, kw: Float): Boolean {
        val slack = config.lengthSlackKeyWidths * kw
        // loops for double letters add drawn arc the sokgraph doesn't have
        val loopAllowance = sok.doubleLetterCount * config.doubleLetterArcKeyWidths * kw
        val effectiveDrawn = (drawnLength - loopAllowance).coerceAtLeast(0f)
        val lo = effectiveDrawn / config.lengthRatioBand - slack
        val hi = effectiveDrawn * config.lengthRatioBand + slack
        return sok.length in lo..hi
    }
}
