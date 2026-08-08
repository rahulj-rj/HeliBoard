// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import helium314.keyboard.gesture.KeyInfo
import helium314.keyboard.gesture.KeyboardGeometry

/** Staggered qwerty geometry scaled to a pixel width (same layout as the :gesture test fixture). */
object Qwerty {
    const val COLUMNS = 10
    const val ROWS = 3           // letter rows
    const val BOTTOM_ROWS = 1    // bottom row carrying the period key (apostrophe waypoint)
    const val KEY_HEIGHT_FACTOR = 1.35f // key height in key widths (finger-friendly)
    /** Empty space above the top row (in key heights) so caps excursions have room. */
    const val HEADROOM_KEY_HEIGHTS = 1.5f

    private val rows = listOf(
        "qwertyuiop" to 0.0f,
        "asdfghjkl" to 0.5f,
        "zxcvbnm" to 1.5f,
    )

    fun keyHeightForWidth(width: Int): Float = width / COLUMNS.toFloat() * KEY_HEIGHT_FACTOR

    fun heightForWidth(width: Int): Int =
        (keyHeightForWidth(width) * (HEADROOM_KEY_HEIGHTS + ROWS + BOTTOM_ROWS)).toInt()

    fun build(width: Float): KeyboardGeometry {
        val keyW = width / COLUMNS
        val keyH = keyW * KEY_HEIGHT_FACTOR
        val top = HEADROOM_KEY_HEIGHTS * keyH
        val keys = ArrayList<KeyInfo>()
        rows.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { col, c ->
                keys.add(
                    KeyInfo(
                        char = c,
                        centerX = (offset + col + 0.5f) * keyW,
                        centerY = top + (rowIndex + 0.5f) * keyH,
                        width = keyW,
                        height = keyH,
                    )
                )
            }
        }
        // period key on the bottom row (right side, like the real layout); it takes part
        // in gestures only as the apostrophe waypoint ("I'm" = i -> '.' -> m)
        keys.add(KeyInfo(KeyboardGeometry.PERIOD_KEY_CHAR, 8.5f * keyW, top + (ROWS + 0.5f) * keyH, keyW, keyH))
        return KeyboardGeometry(keys)
    }
}
