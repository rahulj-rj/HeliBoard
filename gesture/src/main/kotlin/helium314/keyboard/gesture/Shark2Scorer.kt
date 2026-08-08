// SPDX-License-Identifier: GPL-3.0-only
// SHARK² scorer (Kristensson & Zhai, UIST 2004): shape channel over normalized
// paths + location channel in keyboard coordinates, fused Gaussian-style.
package helium314.keyboard.gesture

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class Shark2Config(
    /** Number of resampled points for both channels. */
    val sampleCount: Int = 40,
    /** Gaussian sigma of the shape channel (normalized-shape units). */
    val shapeSigma: Float = 0.2f,
    /** Gaussian sigma of the location channel (key widths). */
    val locationSigma: Float = 0.75f,
    /**
     * Location-channel end-point emphasis: weight of the first/last sample relative
     * to the middle (SHARK² alpha weighting — ends carry more information).
     */
    val endpointEmphasis: Float = 2f,
)

class Shark2Scorer(private val config: Shark2Config = Shark2Config()) : Scorer {
    override val name = "shark2"

    override fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float {
        if (gesture.points.isEmpty() || sokgraph.points.isEmpty()) return Float.MAX_VALUE
        val n = config.sampleCount

        val gx = FloatArray(gesture.points.size) { gesture.points[it].x }
        val gy = FloatArray(gesture.points.size) { gesture.points[it].y }
        val tx = FloatArray(sokgraph.points.size) { sokgraph.points[it].x }
        val ty = FloatArray(sokgraph.points.size) { sokgraph.points[it].y }

        val (gRx, gRy) = Geom.resampleToN(padIfSingle(gx), padIfSingle(gy), n)
        val (tRx, tRy) = Geom.resampleToN(padIfSingle(tx), padIfSingle(ty), n)

        // ---- shape channel: translate/scale-normalize both, mean pointwise distance ----
        val gNx = gRx.copyOf(); val gNy = gRy.copyOf()
        val tNx = tRx.copyOf(); val tNy = tRy.copyOf()
        Geom.normalize(gNx, gNy)
        Geom.normalize(tNx, tNy)
        var shapeDist = 0f
        for (i in 0 until n) {
            val dx = gNx[i] - tNx[i]
            val dy = gNy[i] - tNy[i]
            shapeDist += sqrt(dx * dx + dy * dy)
        }
        shapeDist /= n

        // ---- location channel: unnormalized distances in keyboard coordinates ----
        val kw = geometry.keyWidth
        var locDist = 0f
        var weightSum = 0f
        val half = (n - 1) / 2f
        for (i in 0 until n) {
            val dx = gRx[i] - tRx[i]
            val dy = gRy[i] - tRy[i]
            // alpha weighting: endpoints emphasized
            val u = if (half <= 0f) 0f else (i - half) / half // -1..1
            val w = 1f + (config.endpointEmphasis - 1f) * u * u
            locDist += w * sqrt(dx * dx + dy * dy)
            weightSum += w
        }
        locDist = locDist / weightSum / kw // in key widths

        // ---- Gaussian channel fusion (SHARK²): product of per-channel Gaussians;
        // score = -ln(p_shape * p_location), then sqrt back to a distance-like scale
        // so the shared frequency-ranking formula behaves comparably across scorers.
        val zShape = shapeDist / config.shapeSigma
        val zLoc = locDist / config.locationSigma
        val negLog = -ln(exp(-0.5 * zShape * zShape).toFloat() * exp(-0.5 * zLoc * zLoc).toFloat() + 1e-30f)
        return sqrt(negLog).coerceAtLeast(Scorer.MIN_SCORE)
    }

    /** resampleToN needs ≥1 point; duplicate a lone point so degenerate templates work. */
    private fun padIfSingle(a: FloatArray): FloatArray =
        if (a.size == 1) floatArrayOf(a[0], a[0]) else a
}
