// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in local corpus of real swipes from everyday typing (M4 tuning data for the in-tree decoder).
 *
 * One JSONL line per completed swipe: raw touch path in keyboard pixels, the letter-key geometry
 * of the keyboard it was drawn on, and the decoder's top candidates (top-1 is what got committed,
 * serving as pseudo-label). If the user then picks another suggestion or deletes the swiped word,
 * a follow-up line `{"type":"final","ref":id,...}` records the correction.
 *
 * Schema shares `points` / `locale` / `committed` with gesturelab's and the trainer's swipes.jsonl.
 * Everything stays in the app's external files dir (`gesture_corpus.jsonl`); nothing is uploaded.
 * Gesture typing is off in password fields, so those never reach here.
 */
object GestureCorpusRecorder {
    private const val TAG = "GestureCorpusRecorder"
    private const val FILE_NAME = "gesture_corpus.jsonl"
    private const val MAX_CANDIDATES = 5

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gesture-corpus").apply { isDaemon = true } }
    private val counter = AtomicLong(0)
    @Volatile private var file: File? = null
    /** id of the most recent swipe that is still the composing word, or -1 */
    @Volatile private var pendingId = -1L

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    fun isEnabled(): Boolean = Settings.getValues().mRecordGestureCorpus && file != null

    fun corpusFile(): File? = file

    /** Called with the final (tail) batch-input decode; [candidates] are the decoder's ranked results. */
    fun onSwipe(composedData: ComposedData, keyboard: Keyboard, candidates: Collection<SuggestedWordInfo>, localeTag: String) {
        if (!isEnabled()) return
        val pointers = composedData.mInputPointers
        val size = pointers.pointerSize
        if (size < 2) return
        val xs = pointers.xCoordinates.copyOf(size)
        val ys = pointers.yCoordinates.copyOf(size)
        val ts = pointers.times.copyOf(size)
        // the native decoder reports the same word once per dictionary it was found in; keep the best-ranked
        val cands = candidates.distinctBy { it.mWord }.take(MAX_CANDIDATES).map { it.mWord to it.mScore }
        val keys = letterKeys(keyboard)
        val kbW = keyboard.mOccupiedWidth
        val kbH = keyboard.mOccupiedHeight
        val layoutName = keyboard.mId.mSubtype.mainLayoutName
        val id = counter.incrementAndGet()
        pendingId = id
        val time = System.currentTimeMillis()
        executor.execute {
            try {
                val obj = JSONObject()
                obj.put("type", "swipe")
                obj.put("id", id)
                obj.put("time", time)
                obj.put("source", "keyboard")
                obj.put("locale", localeTag)
                obj.put("layout", layoutName)
                obj.put("committed", cands.firstOrNull()?.first ?: "")
                val pts = JSONArray()
                for (i in 0 until size) pts.put(JSONArray().put(xs[i]).put(ys[i]).put(ts[i]))
                obj.put("points", pts)
                val candArr = JSONArray()
                for ((w, s) in cands) candArr.put(JSONObject().put("word", w).put("score", s))
                obj.put("candidates", candArr)
                val kb = JSONObject().put("w", kbW).put("h", kbH)
                val keyArr = JSONArray()
                for (k in keys) keyArr.put(JSONArray().put(k.c.toString()).put(k.cx).put(k.cy).put(k.w).put(k.h))
                kb.put("keys", keyArr)
                obj.put("keyboard", kb)
                append(obj)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record swipe", e)
            }
        }
    }

    /** The user replaced the pending swiped word with [word] via the suggestion strip. */
    fun onSuggestionPicked(word: String) = correction("pick", word)

    /** The user deleted the pending swiped word (backspace on a batch word). */
    fun onWordDeleted() = correction("deleted", null)

    /** Any other commit / new word: the pending swipe is settled as-is. */
    fun onWordSettled() { pendingId = -1L }

    private fun correction(how: String, word: String?) {
        val id = pendingId
        if (id < 0 || !isEnabled()) return
        pendingId = -1L
        val time = System.currentTimeMillis()
        executor.execute {
            try {
                val obj = JSONObject().put("type", "final").put("ref", id).put("time", time).put("how", how)
                if (word != null) obj.put("final", word)
                append(obj)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record correction", e)
            }
        }
    }

    private fun append(obj: JSONObject) {
        file?.appendText(obj.toString() + "\n")
    }

    private class KeyGeom(val c: Char, val cx: Float, val cy: Float, val w: Float, val h: Float)

    private fun letterKeys(keyboard: Keyboard): List<KeyGeom> {
        val seen = HashSet<Char>()
        val out = ArrayList<KeyGeom>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            if (code <= 0 || (code != '.'.code && !Character.isLetter(code))) continue
            val c = Character.toLowerCase(code).toChar()
            if (!seen.add(c)) continue
            out.add(KeyGeom(c, key.x + key.width / 2f, key.y + key.height / 2f, key.width.toFloat(), key.height.toFloat()))
        }
        return out
    }
}
