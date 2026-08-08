// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import helium314.keyboard.gesture.KeyInfo
import helium314.keyboard.gesture.KeyboardGeometry

/** Staggered qwerty geometry scaled to a pixel width (same layout as the :gesture test fixture). */
object Qwerty {
    const val COLUMNS = 10
    const val ROWS = 3
    const val KEY_HEIGHT_FACTOR = 1.35f // key height in key widths (finger-friendly)

    private val rows = listOf(
        "qwertyuiop" to 0.0f,
        "asdfghjkl" to 0.5f,
        "zxcvbnm" to 1.5f,
    )

    fun heightForWidth(width: Int): Int = (width / COLUMNS.toFloat() * KEY_HEIGHT_FACTOR * ROWS).toInt()

    fun build(width: Float): KeyboardGeometry {
        val keyW = width / COLUMNS
        val keyH = keyW * KEY_HEIGHT_FACTOR
        val keys = ArrayList<KeyInfo>()
        rows.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { col, c ->
                keys.add(
                    KeyInfo(
                        char = c,
                        centerX = (offset + col + 0.5f) * keyW,
                        centerY = (rowIndex + 0.5f) * keyH,
                        width = keyW,
                        height = keyH,
                    )
                )
            }
        }
        return KeyboardGeometry(keys)
    }
}
