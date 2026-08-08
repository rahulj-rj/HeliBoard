// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.InflectionType
import helium314.keyboard.gesture.KeyboardGeometry
import helium314.keyboard.gesture.PreprocessedGesture

/**
 * Renders a full-width staggered qwerty, captures swipes over it, and overlays
 * the last swipe: raw path, resampled path, and inflection points color-coded
 * by type (see [inflectionColor] / the legend in MainActivity).
 */
class KeyboardSwipeView(context: Context) : View(context) {

    var onSwipe: ((List<GesturePoint>) -> Unit)? = null

    var geometry: KeyboardGeometry = Qwerty.build(1000f)
        private set

    private val raw = ArrayList<GesturePoint>()
    private var overlayRaw: List<GesturePoint> = emptyList()
    private var overlay: PreprocessedGesture? = null

    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt(); style = Paint.Style.FILL }
    private val keyBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBBBBBB.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val keyLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textAlign = Paint.Align.CENTER }
    private val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x903F51B5.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val resampledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF009688.toInt(); style = Paint.Style.FILL }
    private val inflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val inflectionRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, Qwerty.heightForWidth(width))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = Qwerty.build(w.toFloat())
        keyLabel.textSize = geometry.keyHeight * 0.35f
        overlay = null
        overlayRaw = emptyList()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                raw.clear()
                raw.add(GesturePoint(event.x, event.y, event.eventTime))
                overlay = null
                overlayRaw = emptyList()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    raw.add(GesturePoint(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h)))
                }
                raw.add(GesturePoint(event.x, event.y, event.eventTime))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                raw.add(GesturePoint(event.x, event.y, event.eventTime))
                if (raw.size > 1) onSwipe?.invoke(ArrayList(raw))
                raw.clear()
            }
            else -> return false
        }
        return true
    }

    /** Called by MainActivity after decoding so raw + preprocessed overlays match. */
    fun showOverlay(rawPoints: List<GesturePoint>, preprocessed: PreprocessedGesture) {
        overlayRaw = rawPoints
        overlay = preprocessed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // keys
        for (k in geometry.keys) {
            val l = k.centerX - k.width / 2 + 2
            val t = k.centerY - k.height / 2 + 2
            val r = k.centerX + k.width / 2 - 2
            val b = k.centerY + k.height / 2 - 2
            canvas.drawRoundRect(l, t, r, b, 8f, 8f, keyFill)
            canvas.drawRoundRect(l, t, r, b, 8f, 8f, keyBorder)
            val textY = k.centerY - (keyLabel.descent() + keyLabel.ascent()) / 2
            canvas.drawText(k.char.uppercaseChar().toString(), k.centerX, textY, keyLabel)
        }
        // in-progress or last raw path
        val rawToDraw = if (raw.isNotEmpty()) raw else overlayRaw
        if (rawToDraw.size > 1) {
            val path = Path()
            path.moveTo(rawToDraw[0].x, rawToDraw[0].y)
            for (i in 1 until rawToDraw.size) path.lineTo(rawToDraw[i].x, rawToDraw[i].y)
            canvas.drawPath(path, rawPaint)
        }
        // resampled path (dots) + inflection points
        overlay?.let { g ->
            for (p in g.points) canvas.drawCircle(p.x, p.y, 4f, resampledPaint)
            for (ip in g.inflections) {
                inflectionPaint.color = inflectionColor(ip.type)
                val radius = 10f + 6f * ip.confidence
                canvas.drawCircle(ip.x, ip.y, radius, inflectionPaint)
                canvas.drawCircle(ip.x, ip.y, radius, inflectionRing)
            }
        }
    }

    companion object {
        fun inflectionColor(type: InflectionType): Int = when (type) {
            InflectionType.PEN_DOWN -> 0xFF4CAF50.toInt()        // green
            InflectionType.PEN_UP -> 0xFFF44336.toInt()          // red
            InflectionType.ANGLE_THRESHOLD -> 0xFFFF9800.toInt() // orange
            InflectionType.PAUSE -> 0xFF9C27B0.toInt()           // purple
            InflectionType.ROW_CHANGE -> 0xFF00BCD4.toInt()      // cyan
            InflectionType.DOUBLE_LETTER -> 0xFFE91E63.toInt()   // pink
        }
    }
}
