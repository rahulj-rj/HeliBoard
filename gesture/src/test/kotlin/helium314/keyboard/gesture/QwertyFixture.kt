// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/** Hardcoded standard qwerty grid for JVM tests (px units, arbitrary but realistic scale). */
object QwertyFixture {
    const val KEY_WIDTH = 100f
    const val KEY_HEIGHT = 120f

    // row → horizontal offset in key widths (standard qwerty stagger)
    private val rows = listOf(
        "qwertyuiop" to 0.0f,
        "asdfghjkl" to 0.5f,
        "zxcvbnm" to 1.5f,
    )

    val geometry: KeyboardGeometry by lazy {
        val keys = ArrayList<KeyInfo>()
        rows.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { col, c ->
                keys.add(
                    KeyInfo(
                        char = c,
                        centerX = (offset + col + 0.5f) * KEY_WIDTH,
                        centerY = (rowIndex + 0.5f) * KEY_HEIGHT,
                        width = KEY_WIDTH,
                        height = KEY_HEIGHT,
                    )
                )
            }
        }
        // period key on the bottom row (right of the space bar, roughly under 'm'):
        // participates in gestures only as the apostrophe waypoint
        keys.add(KeyInfo(KeyboardGeometry.PERIOD_KEY_CHAR, 8.5f * KEY_WIDTH, 3.5f * KEY_HEIGHT, KEY_WIDTH, KEY_HEIGHT))
        KeyboardGeometry(keys)
    }
}
