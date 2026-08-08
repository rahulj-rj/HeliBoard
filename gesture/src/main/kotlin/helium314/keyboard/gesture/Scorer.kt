// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * A scorer is a pure function of (preprocessed path, candidate sokgraph) → score.
 * Lower scores are better. Scores should be in "key width" scale so the shared
 * ranking formula (score * (ln(MAX_FREQ / freq) + 1)) works across scorers.
 */
interface Scorer {
    val name: String
    fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float

    companion object {
        /** Floor so the frequency multiplier in the ranking formula always has effect. */
        const val MIN_SCORE = 0.05f
    }
}
