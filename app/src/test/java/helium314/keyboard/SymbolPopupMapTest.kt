// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.KeyboardParser
import helium314.keyboard.latin.settings.Defaults
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolPopupMapTest {
    @Test fun defaultMapMatchesFormerHardCodedOverlay() {
        val map = KeyboardParser.parseSymbolPopupMap(Defaults.PREF_SYMBOL_POPUP_MAP)
        assertEquals(listOf("~", "`"), map["q"])
        assertEquals(listOf("×"), map["w"]); assertEquals(listOf("÷"), map["e"])
        assertEquals(listOf("_"), map["o"]); assertEquals(listOf("-"), map["p"])
        assertEquals(listOf("—", "–"), map["d"]); assertEquals(listOf("…"), map["f"])
        assertEquals(listOf("'"), map["j"]); assertEquals(listOf("\""), map["k"]); assertEquals(listOf("/"), map["l"])
        assertEquals(listOf("="), map["z"]); assertEquals(listOf("\\"), map["x"]); assertEquals(listOf("?"), map["m"])
        assertEquals(26, map.size)
        assertTrue(KeyboardParser.isValidSymbolPopupMap(Defaults.PREF_SYMBOL_POPUP_MAP))
    }

    @Test fun parserSkipsMalformedEntriesAndKeepsFirstDuplicate() {
        val map = KeyboardParser.parseSymbolPopupMap("  Q~  1!  w  w₹  e€£ ")
        assertEquals(mapOf("q" to listOf("~"), "w" to listOf("₹"), "e" to listOf("€", "£")), map)
        assertTrue(KeyboardParser.parseSymbolPopupMap("").isEmpty())
    }

    @Test fun validatorRejectsBadInput() {
        assertFalse(KeyboardParser.isValidSymbolPopupMap("q~ q`"))   // duplicate letter
        assertFalse(KeyboardParser.isValidSymbolPopupMap("q~ w"))    // entry without symbols
        assertFalse(KeyboardParser.isValidSymbolPopupMap("1!"))      // not a letter
        assertTrue(KeyboardParser.isValidSymbolPopupMap("q~` ü€"))   // non-ascii letters are fine
        assertTrue(KeyboardParser.isValidSymbolPopupMap(""))         // empty = overlay off
    }
}
