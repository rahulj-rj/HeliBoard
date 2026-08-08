// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import android.content.Context
import helium314.keyboard.gesture.Vocabulary

/**
 * Loads the dev vocabulary from assets/vocabulary.txt: whitespace-separated
 * "word frequency" pairs; lines starting with '#' are comments.
 *
 * To test with a real vocabulary, replace assets/vocabulary.txt with a larger
 * list in the same format (e.g. a top-10k english frequency list scaled to 1..255).
 */
object LabVocabulary {
    fun load(context: Context): Vocabulary {
        val entries = ArrayList<Pair<String, Int>>()
        context.assets.open("vocabulary.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val tokens = line.trim().split(Regex("\\s+"))
                var i = 0
                while (i + 1 < tokens.size) {
                    val freq = tokens[i + 1].toIntOrNull()
                    if (freq != null) entries.add(Pair(tokens[i], freq))
                    i += 2
                }
            }
        }
        return Vocabulary(entries)
    }
}
