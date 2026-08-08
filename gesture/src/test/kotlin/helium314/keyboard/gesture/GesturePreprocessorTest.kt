// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GesturePreprocessorTest {
    private val geometry = QwertyFixture.geometry
    private val preprocessor = GesturePreprocessor()

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float, points: Int = 30, msPerStep: Long = 10): List<GesturePoint> =
        (0 until points).map { i ->
            val t = i / (points - 1f)
            GesturePoint(x1 + t * (x2 - x1), y1 + t * (y2 - y1), i * msPerStep)
        }

    @Test
    fun `resampled points have roughly uniform spacing`() {
        val g = preprocessor.preprocess(linePath(50f, 60f, 950f, 60f), geometry)
        val spacing = geometry.keyWidth * 0.25f
        for (i in 1 until g.points.size - 1) { // last interval may be shorter
            val d = PreprocessedGesture.dist(g.points[i - 1], g.points[i])
            assertTrue(abs(d - spacing) < spacing * 0.25f, "spacing $d at $i, expected ~$spacing")
        }
    }

    @Test
    fun `pen down and pen up inflections are always present at path ends`() {
        val g = preprocessor.preprocess(linePath(50f, 60f, 950f, 180f), geometry)
        assertEquals(InflectionType.PEN_DOWN, g.inflections.first().type)
        assertEquals(InflectionType.PEN_UP, g.inflections.last().type)
        assertEquals(0, g.inflections.first().index)
        assertEquals(g.points.size - 1, g.inflections.last().index)
    }

    @Test
    fun `right-angle corner produces an angle inflection near the corner`() {
        // path q-row rightwards then straight down: corner at (750, 60) ~ key u
        val leg1 = linePath(50f, 60f, 750f, 60f, 30)
        val leg2 = linePath(750f, 60f, 750f, 300f, 12, 10).map { GesturePoint(it.x, it.y, it.t + 300) }
        val g = preprocessor.preprocess(leg1 + leg2.drop(1), geometry)
        val corner = g.inflections.filter { it.type == InflectionType.ANGLE_THRESHOLD }
        assertTrue(corner.isNotEmpty(), "expected an ANGLE_THRESHOLD inflection, got ${g.inflections}")
        val best = corner.minByOrNull { abs(it.x - 750f) + abs(it.y - 60f) }!!
        assertTrue(abs(best.x - 750f) < geometry.keyWidth && abs(best.y - 60f) < geometry.keyHeight,
            "corner inflection at (${best.x}, ${best.y}) too far from (750, 60)")
    }

    @Test
    fun `double letter loop produces a DOUBLE_LETTER inflection`() {
        val path = SyntheticPathGenerator.idealPath("loop", geometry)
        val g = preprocessor.preprocess(path, geometry)
        assertTrue(g.inflections.any { it.type == InflectionType.DOUBLE_LETTER },
            "expected DOUBLE_LETTER inflection for 'loop', got ${g.inflections.map { it.type }}")
    }

    @Test
    fun `straight swipe has no strong interior inflections`() {
        val g = preprocessor.preprocess(linePath(50f, 60f, 950f, 60f), geometry)
        val interior = g.inflections.filter { it.type != InflectionType.PEN_DOWN && it.type != InflectionType.PEN_UP }
        assertTrue(interior.isEmpty(), "straight line should have no interior inflections, got $interior")
    }

    @Test
    fun `dwell shows up as a PAUSE inflection`() {
        // move, dwell mid-path (time passes, position steady), move on
        val leg1 = linePath(50f, 60f, 450f, 60f, 20, 10)
        val dwellT0 = leg1.last().t
        val leg2 = linePath(450f, 60f, 850f, 60f, 20, 10).map { GesturePoint(it.x, it.y, it.t + dwellT0 + 600) }
        val g = preprocessor.preprocess(leg1 + leg2.drop(1), geometry)
        assertTrue(g.inflections.any { it.type == InflectionType.PAUSE },
            "expected PAUSE inflection, got ${g.inflections.map { it.type }}")
    }

    @Test
    fun `sokgraph collapses double letters and marks them`() {
        val sok = SokgraphBuilder.build("hello", geometry)!!
        assertEquals(4, sok.points.size) // h e l o
        assertTrue(sok.points[2].isDouble) // the collapsed 'll'
        assertEquals(1, sok.doubleLetterCount)
    }
}
