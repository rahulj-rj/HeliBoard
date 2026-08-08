// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates synthetic swipe paths for a word on a keyboard geometry:
 * the ideal polyline through key centers (with small loops for double letters),
 * plus noisy variants (Gaussian jitter, corner rounding, speed variation).
 */
object SyntheticPathGenerator {

    class NoiseParams(
        /** Gaussian jitter sigma in key widths. */
        val jitterKeyWidths: Float = 0.15f,
        /** Corner rounding: moving-average half-window applied after sampling (0 = off). */
        val cornerRounding: Int = 2,
        /** Speed variation amplitude: dt is scaled by 1 ± this (smoothly varying). */
        val speedVariation: Float = 0.5f,
    )

    /**
     * Ideal path: polyline through the key centers of [word]'s letters, sampled at
     * [spacing] px with a constant nominal speed of [speedPxPerMs].
     * Double letters become a small loop at the key (Swype's loop gesture).
     */
    fun idealPath(
        word: String,
        geometry: KeyboardGeometry,
        spacing: Float = geometry.keyWidth / 5f,
        speedPxPerMs: Float = 1.0f,
    ): List<GesturePoint> {
        val anchors = anchorPolyline(word, geometry)
        val sampled = samplePolyline(anchors, spacing)
        // assign timestamps at constant speed
        val out = ArrayList<GesturePoint>(sampled.size)
        var t = 0L
        var prevX = 0f
        var prevY = 0f
        sampled.forEachIndexed { i, (x, y) ->
            if (i > 0) {
                val d = dist(prevX, prevY, x, y)
                t += (d / speedPxPerMs).toLong().coerceAtLeast(1L)
            }
            out.add(GesturePoint(x, y, t))
            prevX = x; prevY = y
        }
        return out
    }

    /** Noisy variant of an ideal path; deterministic for a given [seed]. */
    fun noisyPath(
        word: String,
        geometry: KeyboardGeometry,
        seed: Long,
        params: NoiseParams = NoiseParams(),
    ): List<GesturePoint> {
        val rng = Random(seed)
        val ideal = idealPath(word, geometry)
        val sigma = params.jitterKeyWidths * geometry.keyWidth

        // jitter
        var pts = ideal.map {
            GesturePoint(
                it.x + (rng.nextGaussian() * sigma).toFloat(),
                it.y + (rng.nextGaussian() * sigma).toFloat(),
                it.t,
            )
        }

        // corner rounding (moving average, endpoints kept)
        val w = params.cornerRounding
        if (w > 0 && pts.size > 2) {
            pts = pts.mapIndexed { i, p ->
                if (i == 0 || i == pts.size - 1) p
                else {
                    var sx = 0f; var sy = 0f; var n = 0
                    for (j in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(pts.size - 1)) {
                        sx += pts[j].x; sy += pts[j].y; n++
                    }
                    GesturePoint(sx / n, sy / n, p.t)
                }
            }
        }

        // speed variation: rescale dt with a smoothly varying factor
        if (params.speedVariation > 0f && pts.size > 1) {
            val out = ArrayList<GesturePoint>(pts.size)
            out.add(pts[0])
            var t = pts[0].t
            val phase = rng.nextDouble() * 2 * PI
            val cycles = 1 + rng.nextInt(3)
            for (i in 1 until pts.size) {
                val u = i.toDouble() / (pts.size - 1)
                val factor = 1.0 + params.speedVariation * sin(phase + 2 * PI * cycles * u)
                val dt = (pts[i].t - pts[i - 1].t).coerceAtLeast(1L)
                t += (dt * factor).toLong().coerceAtLeast(1L)
                out.add(GesturePoint(pts[i].x, pts[i].y, t))
            }
            pts = out
        }
        return pts
    }

    /**
     * Inject a caps excursion: at sample [atIndex] the path detours straight up to
     * [heightKeyHeights] key heights above the keyboard's top edge and comes back,
     * then continues. Timestamps are recomputed at constant speed so they stay monotonic.
     */
    fun withExcursion(
        path: List<GesturePoint>,
        geometry: KeyboardGeometry,
        atIndex: Int,
        heightKeyHeights: Float = 1.2f,
        speedPxPerMs: Float = 1.0f,
    ): List<GesturePoint> {
        require(path.isNotEmpty())
        val i = atIndex.coerceIn(0, path.size - 1)
        val base = path[i]
        val apexY = geometry.topEdge - heightKeyHeights * geometry.keyHeight
        val spike = ArrayList<Pair<Float, Float>>()
        val steps = 6
        for (s in 1..steps) spike.add(Pair(base.x, base.y + (apexY - base.y) * s / steps)) // up
        for (s in steps - 1 downTo 0) spike.add(Pair(base.x, base.y + (apexY - base.y) * s / steps)) // down
        val merged = ArrayList<Pair<Float, Float>>(path.size + spike.size)
        for (j in 0..i) merged.add(Pair(path[j].x, path[j].y))
        merged.addAll(spike)
        for (j in i + 1 until path.size) merged.add(Pair(path[j].x, path[j].y))
        // recompute timestamps at constant speed
        val out = ArrayList<GesturePoint>(merged.size)
        var t = 0L
        for ((j, p) in merged.withIndex()) {
            if (j > 0) {
                val prev = merged[j - 1]
                val d = dist(prev.first, prev.second, p.first, p.second)
                t += (d / speedPxPerMs).toLong().coerceAtLeast(1L)
            }
            out.add(GesturePoint(p.first, p.second, t))
        }
        return out
    }

    /** Sample index of [path] closest to the key of `word[letterIndex]` (searching forward). */
    fun indexNearestToLetter(path: List<GesturePoint>, geometry: KeyboardGeometry, word: String, letterIndex: Int): Int {
        val k = geometry.keyForWordChar(word[letterIndex].lowercaseChar()) ?: error("no key")
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for ((i, p) in path.withIndex()) {
            val d = dist(p.x, p.y, k.centerX, k.centerY)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    // ---- internals ----

    /** Key-center anchors; double letters expand into a small loop around the key. */
    private fun anchorPolyline(word: String, geometry: KeyboardGeometry): List<Pair<Float, Float>> {
        val anchors = ArrayList<Pair<Float, Float>>()
        var prev: Char? = null
        for (c in word.lowercase()) {
            val k = geometry.keyForWordChar(c) ?: error("no key for '$c'")
            if (c == prev) {
                // loop gesture: small circle around the key center
                val r = geometry.keyWidth * 0.25f
                for (step in 1..8) {
                    val a = 2 * PI * step / 8
                    anchors.add(Pair(k.centerX + (r * cos(a)).toFloat(), k.centerY + (r * sin(a)).toFloat()))
                }
                anchors.add(Pair(k.centerX, k.centerY))
            } else {
                anchors.add(Pair(k.centerX, k.centerY))
            }
            prev = c
        }
        return anchors
    }

    private fun samplePolyline(anchors: List<Pair<Float, Float>>, spacing: Float): List<Pair<Float, Float>> {
        if (anchors.size == 1) return listOf(anchors[0], anchors[0]) // tap: two identical points
        val out = ArrayList<Pair<Float, Float>>()
        out.add(anchors[0])
        var carry = 0f
        var prev = anchors[0]
        for (i in 1 until anchors.size) {
            val cur = anchors[i]
            var segLen = dist(prev.first, prev.second, cur.first, cur.second)
            while (carry + segLen >= spacing) {
                val need = spacing - carry
                val t = if (segLen <= 1e-6f) 0f else need / segLen
                val nx = prev.first + t * (cur.first - prev.first)
                val ny = prev.second + t * (cur.second - prev.second)
                val np = Pair(nx, ny)
                out.add(np)
                prev = np
                segLen = dist(prev.first, prev.second, cur.first, cur.second)
                carry = 0f
            }
            carry += segLen
            prev = cur
        }
        if (out.last() != anchors.last()) out.add(anchors.last())
        return out
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
