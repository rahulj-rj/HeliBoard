// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.ScoredWord
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of the last own-decoder gesture decode (lab flavor). The IME service and
 * activities (e.g. the Swipe Trainer) share the app process, so a plain in-memory
 * singleton is sufficient for handing the record across.
 */
class LastDecodeRecord(
    /** strictly increasing per decode, so consumers can detect freshness */
    val id: Long,
    val timeMillis: Long,
    val points: List<GesturePoint>,
    /** scorer name -> top-4 candidates with scores (lower is better) */
    val perScorerTop4: Map<String, List<ScoredWord>>,
    val activeScorer: String,
    val localeTag: String,
)

object LastDecodeHolder {
    private val counter = AtomicLong(0)

    @Volatile
    var latest: LastDecodeRecord? = null
        private set

    fun publish(
        points: List<GesturePoint>,
        perScorerTop4: Map<String, List<ScoredWord>>,
        activeScorer: String,
        localeTag: String,
    ) {
        latest = LastDecodeRecord(
            counter.incrementAndGet(), System.currentTimeMillis(),
            points, perScorerTop4, activeScorer, localeTag
        )
    }
}
