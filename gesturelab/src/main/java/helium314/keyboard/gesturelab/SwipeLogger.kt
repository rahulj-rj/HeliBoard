// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import android.content.Context
import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.ScoredWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Appends recorded swipes as JSON lines to swipes.jsonl in the app's external
 * files dir (pullable via `adb pull /sdcard/Android/data/helium314.keyboard.gesturelab/files/swipes.jsonl`),
 * building a real-swipe tuning corpus.
 */
object SwipeLogger {

    fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "swipes.jsonl")

    fun append(
        context: Context,
        intendedWord: String,
        points: List<GesturePoint>,
        resultsByScorer: Map<String, List<ScoredWord>>,
    ): File {
        val obj = JSONObject()
        obj.put("time", System.currentTimeMillis())
        obj.put("intended", intendedWord)
        val pts = JSONArray()
        for (p in points) {
            pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t))
        }
        obj.put("points", pts)
        val res = JSONObject()
        for ((scorer, words) in resultsByScorer) {
            val arr = JSONArray()
            for (w in words.take(4)) {
                arr.put(JSONObject().put("word", w.word).put("score", w.score.toDouble()).put("raw", w.rawScore.toDouble()))
            }
            res.put(scorer, arr)
        }
        obj.put("top4", res)
        val file = logFile(context)
        file.appendText(obj.toString() + "\n")
        return file
    }
}
