// SPDX-License-Identifier: GPL-3.0-only
// Gesture decoder core types. Pure Kotlin — no Android framework imports so the
// decoder is unit-testable on the JVM. See docs/gesture-decoder-spec.md.
// Implemented from expired patents US7098896 / US7453439 and the SHARK² paper
// (Kristensson & Zhai, UIST 2004) only.
package helium314.keyboard.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/** One captured touch point of a gesture stroke. [t] is milliseconds (monotonic). */
data class GesturePoint(val x: Float, val y: Float, val t: Long)

/** Minimal key description. An adapter from HeliBoard's real Keyboard comes in M2. */
data class KeyInfo(
    val char: Char,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/** Minimal keyboard-geometry holder with the lookups the decoder needs. */
class KeyboardGeometry(val keys: List<KeyInfo>) {
    private val byChar: Map<Char, KeyInfo> = keys.associateBy { it.char }

    /** Typical key width/height, used as the natural distance unit of the decoder. */
    val keyWidth: Float = if (keys.isEmpty()) 1f else keys.map { it.width }.average().toFloat()
    val keyHeight: Float = if (keys.isEmpty()) 1f else keys.map { it.height }.average().toFloat()

    /** y of the top edge of the topmost key — the boundary for caps-excursion detection. */
    val topEdge: Float = if (keys.isEmpty()) 0f else keys.minOf { it.centerY - it.height / 2 }

    fun key(c: Char): KeyInfo? = byChar[c]

    /**
     * Key a WORD character is gestured on. Letters map to their own key; apostrophes map
     * to the PERIOD key (OG-Swype behavior: "I'm" is swiped i → '.' → m). Returns null if
     * the keyboard has no key for the character (word not gesture-decodable here).
     */
    fun keyForWordChar(c: Char): KeyInfo? = when (c) {
        '\'', '’' -> byChar[PERIOD_KEY_CHAR]
        else -> byChar[c.lowercaseChar()]
    }

    fun nearestKey(x: Float, y: Float): KeyInfo? = keys.minByOrNull { distSq(it, x, y) }

    /** All keys whose center is within [radius] of (x, y). */
    fun keysNear(x: Float, y: Float, radius: Float): List<KeyInfo> {
        val r2 = radius * radius
        return keys.filter { distSq(it, x, y) <= r2 }
    }

    private fun distSq(k: KeyInfo, x: Float, y: Float): Float {
        val dx = k.centerX - x
        val dy = k.centerY - y
        return dx * dx + dy * dy
    }

    companion object {
        /** The special non-letter key that may be part of word gestures (apostrophe mapping). */
        const val PERIOD_KEY_CHAR = '.'
    }
}

/**
 * Inflection-point classification per US7453439. Each class carries its own
 * confidence weight (how strongly the point demands a matching letter).
 */
enum class InflectionType {
    PEN_DOWN,        // stroke start — must match the first letter
    PEN_UP,          // stroke end — must match the last letter
    ANGLE_THRESHOLD, // direction change above a (speed-adaptive) angle threshold
    PAUSE,           // dwell: finger slowed/stopped over a key
    ROW_CHANGE,      // vertical direction reversal crossing row boundaries
    DOUBLE_LETTER,   // small loop or tight back-and-forth (Swype's double-letter gesture)
}

/**
 * A detected inflection point.
 * @param index index into the resampled point list
 * @param confidence 0..1 — how strongly this point demands a matched letter
 * @param speedFactor local speed relative to the stroke's mean speed (1 = average);
 *        used for speed-adaptive tolerance (faster ⇒ more tolerant, US7453439)
 */
data class InflectionPoint(
    val index: Int,
    val x: Float,
    val y: Float,
    val type: InflectionType,
    val confidence: Float,
    val speedFactor: Float,
)

/** Output of preprocessing: resampled/smoothed path + classified inflection points. */
class PreprocessedGesture(
    val points: List<GesturePoint>,
    val inflections: List<InflectionPoint>,
    val pathLength: Float,
    val meanSpeed: Float, // px per ms; 0 if no time data
    /**
     * Arc positions (on THIS preprocessed path) where a caps excursion left the keyboard:
     * the letter matched nearest before such a position gets capitalized. Excursion points
     * themselves were stripped before resampling/inflection detection (they would otherwise
     * corrupt both scoring channels), so these junctions are the only trace left.
     */
    val excursionArcs: List<Float> = emptyList(),
) {
    /** Cumulative arc length up to each point (size == points.size). */
    val cumulativeLength: FloatArray = FloatArray(points.size).also { cum ->
        var acc = 0f
        for (i in 1 until points.size) {
            acc += dist(points[i - 1], points[i])
            cum[i] = acc
        }
    }

    /** Count of inflection points whose confidence is at least [minConfidence]. */
    fun strongInflectionCount(minConfidence: Float): Int =
        inflections.count { it.confidence >= minConfidence }

    /** Minimum distance from (x, y) to the drawn polyline. */
    fun distanceToPath(x: Float, y: Float): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) {
            val dx = points[0].x - x
            val dy = points[0].y - y
            return sqrt(dx * dx + dy * dy)
        }
        var best = Float.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = pointToSegment(x, y, points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Distance and arc-length position where the path first comes acceptably close
     * to (x, y) at or after arc position [fromArc]. "Acceptably close" means within
     * [acceptRadius]; within the first such contiguous region the minimum-distance
     * point wins. This deliberately prefers the EARLIEST acceptable pass over the
     * globally nearest one — keys revisited later in the stroke (e.g. the two 'e's
     * of "people") must not swallow the arc position of earlier letters.
     * If the path never gets within [acceptRadius], the global minimum after
     * [fromArc] is returned (the caller's radius check will then reject it).
     */
    fun nearestArcPositionFrom(x: Float, y: Float, fromArc: Float, acceptRadius: Float): Pair<Float, Float> {
        var bestDist = Float.MAX_VALUE
        var bestArc = fromArc
        var inRegion = false
        var regionBest = Float.MAX_VALUE
        var regionArc = fromArc
        for (i in points.indices) {
            if (cumulativeLength[i] < fromArc) continue
            val dx = points[i].x - x
            val dy = points[i].y - y
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDist) {
                bestDist = d
                bestArc = cumulativeLength[i]
            }
            if (d <= acceptRadius) {
                inRegion = true
                if (d < regionBest) {
                    regionBest = d
                    regionArc = cumulativeLength[i]
                }
            } else if (inRegion) {
                return Pair(regionBest, regionArc) // first acceptable region ended
            }
        }
        return if (inRegion) Pair(regionBest, regionArc) else Pair(bestDist, bestArc)
    }

    companion object {
        fun dist(a: GesturePoint, b: GesturePoint): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }

        /** Distance from point (px, py) to segment (x1,y1)-(x2,y2). */
        fun pointToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val vx = x2 - x1
            val vy = y2 - y1
            val len2 = vx * vx + vy * vy
            val t = if (len2 <= 0f) 0f else (((px - x1) * vx + (py - y1) * vy) / len2).coerceIn(0f, 1f)
            val cx = x1 + t * vx
            val cy = y1 + t * vy
            val dx = px - cx
            val dy = py - cy
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/**
 * The ideal polyline ("sokgraph") of a candidate word: key centers of its letters,
 * with consecutive identical letters collapsed to one point marked [SokPoint.isDouble].
 */
class Sokgraph(val word: String, val points: List<SokPoint>, val length: Float) {
    val doubleLetterCount: Int get() = points.count { it.isDouble }
}

data class SokPoint(val char: Char, val x: Float, val y: Float, val isDouble: Boolean)

object SokgraphBuilder {
    /** Returns null if any character of [word] has no key in [geometry] (apostrophes map to the period key). */
    fun build(word: String, geometry: KeyboardGeometry): Sokgraph? {
        if (word.isEmpty()) return null
        val pts = ArrayList<SokPoint>(word.length)
        var prev: Char? = null
        for (c in word) {
            val lc = c.lowercaseChar()
            if (lc == prev) {
                // collapse consecutive identical letters, mark as double
                val last = pts.removeAt(pts.size - 1)
                pts.add(last.copy(isDouble = true))
            } else {
                val k = geometry.keyForWordChar(lc) ?: return null
                pts.add(SokPoint(lc, k.centerX, k.centerY, false))
            }
            prev = lc
        }
        var len = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i - 1].x
            val dy = pts[i].y - pts[i - 1].y
            len += sqrt(dx * dx + dy * dy)
        }
        return Sokgraph(word, pts, len)
    }
}

/** A ranked decoding result. Lower [score] is better (patent ranking formula). */
data class ScoredWord(
    val word: String,
    val score: Float,     // final rank score: rawScore * (ln(MAX_FREQ / freq) + 1)
    val rawScore: Float,  // scorer output before frequency weighting
    val frequency: Int,
)

/** Small helpers shared by scorers. */
object Geom {
    /**
     * Resample a polyline to exactly [n] points at uniform arc-length spacing.
     * Input/output are flat [x0, y0, x1, y1, ...] arrays. Degenerate (zero-length)
     * inputs yield n copies of the first point.
     */
    fun resampleToN(xs: FloatArray, ys: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
        require(n >= 2)
        val count = xs.size
        if (count == 0) return Pair(FloatArray(n), FloatArray(n))
        val cum = FloatArray(count)
        for (i in 1 until count) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cum[count - 1]
        val outX = FloatArray(n)
        val outY = FloatArray(n)
        if (total <= 1e-6f || count == 1) {
            for (i in 0 until n) {
                outX[i] = xs[0]
                outY[i] = ys[0]
            }
            return Pair(outX, outY)
        }
        var seg = 0
        for (i in 0 until n) {
            val target = total * i / (n - 1)
            while (seg < count - 2 && cum[seg + 1] < target) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen <= 1e-6f) 0f else (target - cum[seg]) / segLen
            outX[i] = xs[seg] + t * (xs[seg + 1] - xs[seg])
            outY[i] = ys[seg] + t * (ys[seg + 1] - ys[seg])
        }
        return Pair(outX, outY)
    }

    /** Normalize in place: translate centroid to origin, scale largest bounding-box side to 1. */
    fun normalize(xs: FloatArray, ys: FloatArray) {
        if (xs.isEmpty()) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var cx = 0f; var cy = 0f
        for (i in xs.indices) {
            cx += xs[i]; cy += ys[i]
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        cx /= xs.size; cy /= ys.size
        val side = maxOf(maxX - minX, maxY - minY)
        val scale = if (side <= 1e-6f) 1f else 1f / side
        for (i in xs.indices) {
            xs[i] = (xs[i] - cx) * scale
            ys[i] = (ys[i] - cy) * scale
        }
    }

    fun weightedDistance(dx: Float, dy: Float, wx: Float, wy: Float): Float =
        sqrt(wx * dx * dx + wy * dy * dy)

    fun angleBetweenDeg(v1x: Float, v1y: Float, v2x: Float, v2y: Float): Float {
        val n1 = sqrt(v1x * v1x + v1y * v1y)
        val n2 = sqrt(v2x * v2x + v2y * v2y)
        if (n1 <= 1e-6f || n2 <= 1e-6f) return 0f
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos.toDouble())).toFloat()
    }

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    fun absDiff(a: Float, b: Float): Float = abs(a - b)
}
