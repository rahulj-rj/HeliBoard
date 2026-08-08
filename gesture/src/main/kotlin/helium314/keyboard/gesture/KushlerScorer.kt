// SPDX-License-Identifier: GPL-3.0-only
// Location-channel scorer per US7098896 / US7453439: classified inflection points
// are matched to key centers with weighted x/y distances (y heavier), thresholds
// scale with stroke speed, and skipped inflection points draw penalties.
package helium314.keyboard.gesture

class KushlerConfig(
    /** x-distance weight inside the weighted distance (column position is less reliable). */
    val weightX: Float = 0.5f,
    /** y-distance weight — heavier, row position is more reliable (spec). */
    val weightY: Float = 1.5f,
    /** How strongly local speed widens the tolerance (US7453439: faster ⇒ more tolerant). */
    val speedTolerance: Float = 0.5f,
    /** Penalty (key widths) for skipping an inflection point, scaled by its confidence. */
    val skipInflectionPenalty: Float = 0.9f,
    /** Letters farther than this (key widths) from the drawn path start accruing cost. */
    val offPathFreeRadius: Float = 0.8f,
    /** Cost per key width once a letter is beyond the free radius. */
    val offPathWeight: Float = 1.0f,
    /** Reward factor when a DOUBLE_LETTER inflection matches a double letter. */
    val doubleMatchFactor: Float = 0.3f,
    /**
     * Penalty when a DOUBLE_LETTER inflection matches a non-double letter. Kept small:
     * 180° reversals at ordinary letters (e.g. "pop") classify as DOUBLE_LETTER too.
     */
    val doubleMismatchPenalty: Float = 0.2f,
    /** Penalty when a word's double letter got no DOUBLE_LETTER gesture. */
    val missingDoublePenalty: Float = 0.35f,
)

class KushlerScorer(private val config: KushlerConfig = KushlerConfig()) : Scorer {
    override val name = "kushler"

    override fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float {
        val inflections = gesture.inflections
        val letters = sokgraph.points
        if (inflections.isEmpty() || letters.isEmpty()) return Float.MAX_VALUE
        val kw = geometry.keyWidth

        // distance from each letter's key center to the drawn path, for off-path penalties
        val offPathCost = FloatArray(letters.size) { j ->
            val d = gesture.distanceToPath(letters[j].x, letters[j].y) / kw
            val base = (d - config.offPathFreeRadius).coerceAtLeast(0f) * config.offPathWeight
            // a double letter that no inflection claims also misses its loop gesture
            if (letters[j].isDouble) base + config.missingDoublePenalty else base
        }

        val m = inflections.size
        val n = letters.size

        if (n == 1) {
            // single-letter word: both endpoints match the same letter
            var total = matchCost(inflections[0], letters[0], kw) + matchCost(inflections[m - 1], letters[0], kw)
            for (i in 1 until m - 1) total += skipCost(inflections[i])
            return (total / maxOf(m, 1)).coerceAtLeast(Scorer.MIN_SCORE)
        }

        // DP alignment: dp[i][j] = min cost for inflections[0..i) vs letters[0..j)
        val big = Float.MAX_VALUE / 4f
        val dp = Array(m + 1) { FloatArray(n + 1) { big } }
        dp[0][0] = 0f
        for (i in 0 until m) {
            for (j in 0 until n) {
                val cur = dp[i][j]
                if (cur >= big) continue
                // match inflection i to letter j
                val mc = constrainedMatchCost(i, j, m, n, inflections, letters, kw)
                if (cur + mc < dp[i + 1][j + 1]) dp[i + 1][j + 1] = cur + mc
                // skip inflection i (not allowed for endpoints)
                if (i != 0 && i != m - 1) {
                    val sc = cur + skipCost(inflections[i])
                    if (sc < dp[i + 1][j]) dp[i + 1][j] = sc
                }
                // skip letter j (mid letters ride along the path; ends may not be skipped)
                if (j != 0 && j != n - 1) {
                    val sc = cur + offPathCost[j]
                    if (sc < dp[i][j + 1]) dp[i][j + 1] = sc
                }
            }
            // allow skipping trailing letters row-wise is handled by the loop above
        }
        // close remaining letters/inflections against the final states
        for (j in 0 until n) {
            if (j != 0 && j != n - 1 && dp[m][j] < big) {
                val sc = dp[m][j] + offPathCost[j]
                if (sc < dp[m][j + 1]) dp[m][j + 1] = sc
            }
        }
        val total = dp[m][n]
        if (total >= big) return Float.MAX_VALUE
        return (total / maxOf(m, n)).coerceAtLeast(Scorer.MIN_SCORE)
    }

    /** Match cost with endpoint pinning: PEN_DOWN↔first letter, PEN_UP↔last letter. */
    private fun constrainedMatchCost(
        i: Int, j: Int, m: Int, n: Int,
        inflections: List<InflectionPoint>, letters: List<SokPoint>, kw: Float,
    ): Float {
        val big = Float.MAX_VALUE / 4f
        val ip = inflections[i]
        if (ip.type == InflectionType.PEN_DOWN && j != 0) return big
        if (ip.type == InflectionType.PEN_UP && j != n - 1) return big
        if (j == 0 && i != 0) return big       // only PEN_DOWN matches the first letter
        if (j == n - 1 && i != m - 1) return big // only PEN_UP matches the last letter
        return matchCost(ip, letters[j], kw)
    }

    private fun matchCost(ip: InflectionPoint, letter: SokPoint, kw: Float): Float {
        val dx = (ip.x - letter.x) / kw
        val dy = (ip.y - letter.y) / kw
        // weighted distance, y heavier (row more reliable than column)
        var d = Geom.weightedDistance(dx, dy, config.weightX, config.weightY)
        // speed-adaptive threshold: faster local speed ⇒ larger tolerance ⇒ lower cost
        val tol = Geom.clamp(1f + config.speedTolerance * (ip.speedFactor - 1f), 0.7f, 1.8f)
        d = d / tol * ip.confidence
        // double-letter gesture handling
        if (ip.type == InflectionType.DOUBLE_LETTER) {
            d = if (letter.isDouble) d * config.doubleMatchFactor
                else d + config.doubleMismatchPenalty
        }
        return d
    }

    private fun skipCost(ip: InflectionPoint): Float = ip.confidence * config.skipInflectionPenalty
}
