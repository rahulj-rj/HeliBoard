// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/** Simple vs advanced settings menu. Simple mode shows a curated subset of each screen;
 *  search always sees everything. The switch sits in the top bar of every settings screen. */
object SettingsMode {
    private var state: MutableState<Boolean>? = null

    /** Compose state mirroring the pref, so toggling re-filters the current screen immediately. */
    fun state(context: Context): MutableState<Boolean> = state
        ?: mutableStateOf(context.prefs().getBoolean(Settings.PREF_ADVANCED_SETTINGS, Defaults.PREF_ADVANCED_SETTINGS)).also { state = it }

    fun isAdvanced(context: Context) = state(context).value

    fun set(context: Context, advanced: Boolean) {
        state(context).value = advanced
        context.prefs().edit { putBoolean(Settings.PREF_ADVANCED_SETTINGS, advanced) }
    }

    /** In simple mode keeps only [simpleKeys] (a screen that passes null is never filtered).
     *  Category headers (string resource ids) survive only if at least one kept item follows them. */
    fun filter(items: List<Any?>, simpleKeys: Set<String>?, advanced: Boolean): List<Any?> {
        if (advanced || simpleKeys == null) return items
        val out = mutableListOf<Any?>()
        var pendingHeader: Int? = null
        for (item in items) {
            when {
                item is Int -> pendingHeader = item
                item is String && item in simpleKeys -> {
                    pendingHeader?.let { out.add(it); pendingHeader = null }
                    out.add(item)
                }
            }
        }
        return out
    }
}
