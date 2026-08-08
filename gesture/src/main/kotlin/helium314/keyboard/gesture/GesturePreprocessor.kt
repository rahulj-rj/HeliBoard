// SPDX-License-Identifier: GPL-3.0-only
// Preprocessing stage of the gesture decoder: smoothing, arc-length resampling,
// and classified inflection-point detection per US7453439.
package helium314.keyboard.gesture

import kotlin.math.sqrt

class PreprocessorConfig(
    /** Resample spacing as a fraction of key width. */
    val resampleSpacingKeyWidths: Float = 0.25f,
    /** Moving-average smoothing half-window (points on each side), applied to raw input. */
    val smoothingHalfWindow: Int = 1,
    /** Base direction-change threshold (degrees) for ANGLE_THRESHOLD inflections. */
    val baseAngleThresholdDeg: Float = 55f,
    /** Angle-detection window: direction vectors span this many resampled points. */
    val angleWindow: Int = 2,
    /** Minimum sample separation between two reported inflection points. */
    val minInflectionSeparation: Int = 3,
    /** A resampled-point dwell longer than this multiple of the median dt is a PAUSE. */
    val pauseDtFactor: Float = 3.5f,
    /** Prominence (in key heights) a y-extremum needs to count as ROW_CHANGE. */
    val rowChangeProminenceKeyHeights: Float = 0.6f,
    /** Turn angle (deg) above which a point counts as a back-and-forth DOUBLE_LETTER cusp. */
    val doubleLetterCuspDeg: Float = 150f,
    /** Loop detection: max distance (key widths) between loop start/end points. */
    val loopCloseKeyWidths: Float = 0.55f,
    /** Loop detection: min arc length (key widths) travelled between them. */
    val loopMinArcKeyWidths: Float = 1.0f,
    /** Loop detection: max arc length (key widths) travelled between them. */
    val loopMaxArcKeyWidths: Float = 3.0f,
    /** How strongly local speed shifts the angle threshold (faster ⇒ lower threshold, more tolerant). */
    val speedAngleAdaptation: Float = 0.25f,
    /**
     * Caps excursion: a run of points above the keyboard's top edge only counts (and is
     * only stripped) if it rises at least this many key heights ABOVE the top edge —
     * grazing the top row must not trigger capitalization.
     */
    val excursionMinHeightKeyHeights: Float = 0.5f,
)

/**
 * Turns a raw stroke into a [PreprocessedGesture]:
 * 1. light moving-average smoothing,
 * 2. resampling to uniform arc-length spacing,
 * 3. inflection-point detection with classification and per-class confidence.
 */
class GesturePreprocessor(private val config: PreprocessorConfig = PreprocessorConfig()) {

    fun preprocess(raw: List<GesturePoint>, geometry: KeyboardGeometry): PreprocessedGesture {
        if (raw.isEmpty()) return PreprocessedGesture(emptyList(), emptyList(), 0f, 0f)
        // strip caps-excursion points FIRST: the vertical detour must never reach
        // resampling/inflection detection/scoring (exit+reentry would read as two
        // huge fake corners and corrupt both channels)
        val (kept, rawExcursionArcs) = stripExcursions(raw, geometry)
        if (kept.size < 2) return PreprocessedGesture(emptyList(), emptyList(), 0f, 0f)
        val keptLength = polylineLength(kept)
        val smoothed = smooth(kept)
        val spacing = geometry.keyWidth * config.resampleSpacingKeyWidths
        val resampled = resample(smoothed, spacing)
        val pathLength = polylineLength(resampled)
        val duration = (resampled.last().t - resampled.first().t).coerceAtLeast(1L)
        val meanSpeed = pathLength / duration
        val inflections = detectInflections(resampled, geometry, meanSpeed)
        // scale excursion arc positions from the raw kept polyline onto the
        // (slightly shorter, smoothed) resampled path
        val scale = if (keptLength <= 0f) 0f else pathLength / keptLength
        val excursionArcs = rawExcursionArcs.map { it * scale }
        return PreprocessedGesture(resampled, inflections, pathLength, meanSpeed, excursionArcs)
    }

    /**
     * Caps-excursion detection (Swype-authentic capitalization gesture): finds contiguous
     * runs of points above the keyboard's top edge. Runs rising at least
     * [PreprocessorConfig.excursionMinHeightKeyHeights] above the edge are STRIPPED
     * (exit and reentry joined) and their junction arc position recorded; shallower
     * grazes are kept as normal path points and trigger nothing.
     *
     * Stripping also swallows the near-vertical in-keyboard stubs adjacent to the run
     * (the finger has to cross the upper rows to leave the keyboard) — otherwise the
     * detour would still read as a fake cusp and drag crossed keys into the corridor.
     */
    private fun stripExcursions(raw: List<GesturePoint>, geometry: KeyboardGeometry): Pair<List<GesturePoint>, List<Float>> {
        val topEdge = geometry.topEdge
        if (raw.none { it.y < topEdge }) return Pair(raw, emptyList())
        val trigger = topEdge - config.excursionMinHeightKeyHeights * geometry.keyHeight
        val xTolerance = 0.6f * geometry.keyWidth
        val n = raw.size
        val strip = BooleanArray(n)
        val junctionStarts = HashSet<Int>() // first stripped index of each qualified excursion

        val stubDepthLimit = topEdge + 1.5f * geometry.keyHeight
        var i = 0
        while (i < n) {
            if (raw[i].y >= topEdge) { i++; continue }
            // contiguous run above the top edge
            var j = i
            var minY = raw[i].y
            while (j + 1 < n && raw[j + 1].y < topEdge) {
                j++
                if (raw[j].y < minY) minY = raw[j].y
            }
            if (minY < trigger) {
                // expand backwards over the near-vertical approach stub (still inside the keyboard)
                var a = i
                while (a > 0 && !strip[a - 1] && raw[a - 1].y >= raw[a].y
                    && raw[a - 1].y < stubDepthLimit
                    && Geom.absDiff(raw[a - 1].x, raw[i].x) <= xTolerance) a--
                // The excursion is an out-and-back: the return retraces the approach. Anchor on
                // the point just before the approach stub (or the stroke start if the excursion
                // IS the start) and strip the return until the path is back closest to it —
                // self-calibrating, so a steep first stroke leg is never swallowed.
                val anchor = raw[if (a > 0) a - 1 else 0]
                var b = j
                if (j < n - 1) {
                    var bestIdx = j + 1
                    var bestDist = Float.MAX_VALUE
                    var arcAfter = 0f
                    var k = j + 1
                    while (k < n && arcAfter <= 3f * geometry.keyHeight) {
                        val d = PreprocessedGesture.dist(raw[k], anchor)
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = k
                        }
                        if (k + 1 < n) arcAfter += PreprocessedGesture.dist(raw[k], raw[k + 1])
                        k++
                    }
                    b = bestIdx - 1 // keep the closest-to-anchor point itself
                }
                for (m in a..b) strip[m] = true
                junctionStarts.add(a)
            }
            // else: graze — keep the points, no excursion
            i = j + 1
        }
        if (junctionStarts.isEmpty()) return Pair(raw, emptyList())

        val kept = ArrayList<GesturePoint>(n)
        val arcs = ArrayList<Float>()
        var arc = 0f
        for (k in 0 until n) {
            if (k in junctionStarts) arcs.add(arc)
            if (strip[k]) continue
            if (kept.isNotEmpty()) arc += PreprocessedGesture.dist(kept.last(), raw[k])
            kept.add(raw[k])
        }
        return Pair(kept, arcs)
    }

    // ---- smoothing ----

    private fun smooth(pts: List<GesturePoint>): List<GesturePoint> {
        val w = config.smoothingHalfWindow
        if (w <= 0 || pts.size <= 2) return pts
        val out = ArrayList<GesturePoint>(pts.size)
        for (i in pts.indices) {
            if (i == 0 || i == pts.size - 1) {
                out.add(pts[i]) // keep endpoints exact (PEN_DOWN / PEN_UP locations matter)
                continue
            }
            var sx = 0f; var sy = 0f; var n = 0
            for (j in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(pts.size - 1)) {
                sx += pts[j].x; sy += pts[j].y; n++
            }
            out.add(GesturePoint(sx / n, sy / n, pts[i].t))
        }
        return out
    }

    // ---- resampling ----

    private fun resample(pts: List<GesturePoint>, spacing: Float): List<GesturePoint> {
        if (pts.size < 2 || spacing <= 0f) return pts
        val out = ArrayList<GesturePoint>()
        out.add(pts.first())
        var prev = pts.first()
        var carry = 0f
        for (i in 1 until pts.size) {
            var cur = pts[i]
            var segLen = PreprocessedGesture.dist(prev, cur)
            while (carry + segLen >= spacing) {
                val need = spacing - carry
                val t = if (segLen <= 1e-6f) 0f else need / segLen
                val nx = prev.x + t * (cur.x - prev.x)
                val ny = prev.y + t * (cur.y - prev.y)
                val nt = prev.t + ((cur.t - prev.t) * t).toLong()
                val np = GesturePoint(nx, ny, nt)
                out.add(np)
                prev = np
                segLen = PreprocessedGesture.dist(prev, cur)
                carry = 0f
            }
            carry += segLen
            prev = cur
        }
        if (out.last() != pts.last()) out.add(pts.last())
        return out
    }

    private fun polylineLength(pts: List<GesturePoint>): Float {
        var len = 0f
        for (i in 1 until pts.size) len += PreprocessedGesture.dist(pts[i - 1], pts[i])
        return len
    }

    // ---- inflection detection ----

    private fun detectInflections(
        pts: List<GesturePoint>,
        geometry: KeyboardGeometry,
        meanSpeed: Float,
    ): List<InflectionPoint> {
        val result = ArrayList<InflectionPoint>()
        if (pts.isEmpty()) return result
        val last = pts.size - 1

        val speedFactors = localSpeedFactors(pts, meanSpeed)

        result.add(InflectionPoint(0, pts[0].x, pts[0].y, InflectionType.PEN_DOWN, 1f, speedFactors[0]))

        if (pts.size > 2) {
            val turnAngles = turnAngles(pts)
            addAngleAndCuspInflections(pts, turnAngles, speedFactors, result)
            addLoopInflections(pts, geometry, speedFactors, result)
            addPauseInflections(pts, speedFactors, result)
            addRowChangeInflections(pts, geometry, speedFactors, result)
        }

        result.add(InflectionPoint(last, pts[last].x, pts[last].y, InflectionType.PEN_UP, 1f, speedFactors[last]))
        return dedupe(result)
    }

    /** Local speed relative to mean speed, per resampled point. */
    private fun localSpeedFactors(pts: List<GesturePoint>, meanSpeed: Float): FloatArray {
        val out = FloatArray(pts.size) { 1f }
        if (meanSpeed <= 0f) return out
        for (i in pts.indices) {
            val a = pts[(i - 1).coerceAtLeast(0)]
            val b = pts[(i + 1).coerceAtMost(pts.size - 1)]
            val dt = (b.t - a.t).coerceAtLeast(1L)
            val v = PreprocessedGesture.dist(a, b) / dt
            out[i] = Geom.clamp(v / meanSpeed, 0.2f, 3f)
        }
        return out
    }

    /** Turn angle (deg) at each interior point, using direction vectors over [PreprocessorConfig.angleWindow]. */
    private fun turnAngles(pts: List<GesturePoint>): FloatArray {
        val w = config.angleWindow
        val out = FloatArray(pts.size)
        for (i in pts.indices) {
            val i0 = (i - w).coerceAtLeast(0)
            val i1 = (i + w).coerceAtMost(pts.size - 1)
            if (i0 == i || i1 == i) continue
            out[i] = Geom.angleBetweenDeg(
                pts[i].x - pts[i0].x, pts[i].y - pts[i0].y,
                pts[i1].x - pts[i].x, pts[i1].y - pts[i].y,
            )
        }
        return out
    }

    private fun addAngleAndCuspInflections(
        pts: List<GesturePoint>,
        angles: FloatArray,
        speedFactors: FloatArray,
        out: MutableList<InflectionPoint>,
    ) {
        for (i in 1 until pts.size - 1) {
            val angle = angles[i]
            // Speed-adaptive threshold (US7453439): faster movement rounds corners,
            // so the required angle drops as local speed rises (more tolerant).
            val thresh = (config.baseAngleThresholdDeg *
                    (1f - config.speedAngleAdaptation * (speedFactors[i] - 1f)))
                .coerceIn(35f, 80f)
            if (angle < thresh) continue
            // local maximum of turn angle only
            if (angles[(i - 1).coerceAtLeast(0)] > angle || angles[(i + 1).coerceAtMost(pts.size - 1)] > angle) continue
            val type = if (angle >= config.doubleLetterCuspDeg) InflectionType.DOUBLE_LETTER
                       else InflectionType.ANGLE_THRESHOLD
            val conf = if (type == InflectionType.DOUBLE_LETTER) 0.85f
                       else 0.6f + 0.4f * Geom.clamp((angle - thresh) / (140f - thresh), 0f, 1f)
            out.add(InflectionPoint(i, pts[i].x, pts[i].y, type, conf, speedFactors[i]))
        }
    }

    /** Small closed loops (Swype's double-letter circle gesture). */
    private fun addLoopInflections(
        pts: List<GesturePoint>,
        geometry: KeyboardGeometry,
        speedFactors: FloatArray,
        out: MutableList<InflectionPoint>,
    ) {
        val kw = geometry.keyWidth
        val closeDist = config.loopCloseKeyWidths * kw
        val minArc = config.loopMinArcKeyWidths * kw
        val maxArc = config.loopMaxArcKeyWidths * kw
        // approximate arc between resampled indices via spacing
        val spacing = kw * config.resampleSpacingKeyWidths
        val minSteps = (minArc / spacing).toInt().coerceAtLeast(3)
        val maxSteps = (maxArc / spacing).toInt().coerceAtLeast(minSteps + 1)
        var i = 0
        while (i < pts.size) {
            var found = -1
            var j = i + minSteps
            while (j <= (i + maxSteps).coerceAtMost(pts.size - 1)) {
                if (PreprocessedGesture.dist(pts[i], pts[j]) <= closeDist) {
                    found = j
                    break
                }
                j++
            }
            if (found > 0) {
                val mid = (i + found) / 2
                out.add(InflectionPoint(mid, pts[mid].x, pts[mid].y, InflectionType.DOUBLE_LETTER, 0.85f, speedFactors[mid]))
                i = found + 1
            } else {
                i++
            }
        }
    }

    /** Dwell points: uniform arc-length resampling makes a pause show up as a large dt. */
    private fun addPauseInflections(
        pts: List<GesturePoint>,
        speedFactors: FloatArray,
        out: MutableList<InflectionPoint>,
    ) {
        if (pts.size < 4) return
        val dts = (1 until pts.size).map { (pts[it].t - pts[it - 1].t).toFloat() }.sorted()
        val median = dts[dts.size / 2]
        if (median <= 0f) return
        for (i in 1 until pts.size - 1) {
            val dt = (pts[i + 1].t - pts[i].t).toFloat()
            if (dt > config.pauseDtFactor * median) {
                out.add(InflectionPoint(i, pts[i].x, pts[i].y, InflectionType.PAUSE, 0.7f, speedFactors[i]))
            }
        }
    }

    /** Vertical direction reversals with row-scale prominence. */
    private fun addRowChangeInflections(
        pts: List<GesturePoint>,
        geometry: KeyboardGeometry,
        speedFactors: FloatArray,
        out: MutableList<InflectionPoint>,
    ) {
        val prominence = config.rowChangeProminenceKeyHeights * geometry.keyHeight
        var i = 1
        while (i < pts.size - 1) {
            val dyPrev = pts[i].y - pts[i - 1].y
            val dyNext = pts[i + 1].y - pts[i].y
            if (dyPrev * dyNext < 0f) { // local y-extremum
                // measure prominence: how far y moved before and after the extremum
                var back = i
                while (back > 0 && (pts[i].y - pts[back].y) * dyPrev >= 0f &&
                    Geom.absDiff(pts[back].y, pts[i].y) < prominence) back--
                var fwd = i
                while (fwd < pts.size - 1 && (pts[fwd].y - pts[i].y) * dyNext >= 0f &&
                    Geom.absDiff(pts[fwd].y, pts[i].y) < prominence) fwd++
                if (Geom.absDiff(pts[back].y, pts[i].y) >= prominence &&
                    Geom.absDiff(pts[fwd].y, pts[i].y) >= prominence) {
                    out.add(InflectionPoint(i, pts[i].x, pts[i].y, InflectionType.ROW_CHANGE, 0.55f, speedFactors[i]))
                }
            }
            i++
        }
    }

    /**
     * Keep at most one inflection per neighborhood ([PreprocessorConfig.minInflectionSeparation]),
     * preferring endpoints, then DOUBLE_LETTER > ANGLE_THRESHOLD > PAUSE > ROW_CHANGE.
     */
    private fun dedupe(inflections: List<InflectionPoint>): List<InflectionPoint> {
        val priority = mapOf(
            InflectionType.PEN_DOWN to 5, InflectionType.PEN_UP to 5,
            InflectionType.DOUBLE_LETTER to 4, InflectionType.ANGLE_THRESHOLD to 3,
            InflectionType.PAUSE to 2, InflectionType.ROW_CHANGE to 1,
        )
        val sorted = inflections.sortedWith(compareBy({ it.index }, { -(priority[it.type] ?: 0) }))
        val out = ArrayList<InflectionPoint>()
        for (ip in sorted) {
            val lastKept = out.lastOrNull()
            if (lastKept != null && ip.index - lastKept.index < config.minInflectionSeparation) {
                // same neighborhood: keep the higher-priority one
                if ((priority[ip.type] ?: 0) > (priority[lastKept.type] ?: 0)) {
                    out[out.size - 1] = ip
                }
            } else {
                out.add(ip)
            }
        }
        // make sure PEN_UP survived (it can collide with a late inflection)
        if (out.none { it.type == InflectionType.PEN_UP }) {
            val penUp = inflections.last { it.type == InflectionType.PEN_UP }
            while (out.isNotEmpty() && penUp.index - out.last().index < config.minInflectionSeparation
                && out.last().type != InflectionType.PEN_DOWN) {
                out.removeAt(out.size - 1)
            }
            out.add(penUp)
        }
        return out
    }
}
