// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * Blend of [KushlerScorer] (inflection/location channel) and [Shark2Scorer]
 * (shape + location channels). Weights are configurable; bakeoff data from the
 * two pure scorers feeds the defaults (spec: v1 default scorer).
 */
class HybridScorer(
    private val kushler: KushlerScorer = KushlerScorer(),
    private val shark2: Shark2Scorer = Shark2Scorer(),
    private val kushlerWeight: Float = 0.5f,
    private val shark2Weight: Float = 0.5f,
) : Scorer {
    override val name = "hybrid"

    override fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float {
        val k = kushler.score(gesture, sokgraph, geometry)
        val s = shark2.score(gesture, sokgraph, geometry)
        if (k == Float.MAX_VALUE || s == Float.MAX_VALUE) return Float.MAX_VALUE
        return (kushlerWeight * k + shark2Weight * s).coerceAtLeast(Scorer.MIN_SCORE)
    }
}
