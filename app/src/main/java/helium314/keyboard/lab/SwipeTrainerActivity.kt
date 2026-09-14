// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.lab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.gesture.ScoredWord
import helium314.keyboard.latin.gesture.LastDecodeHolder
import helium314.keyboard.latin.gesture.LastDecodeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Swipe Trainer (HeliBoard Lab only): swipe a configurable word list several times
 * with the Lab keyboard and compare all three decoder scorers without friction.
 *
 * Flow: config -> run (target word shown, swipe into the field, committed word is
 * compared against the target; the fresh [LastDecodeRecord] provides per-scorer
 * top-4 lists which are tallied and logged as JSONL) -> summary (per word x scorer
 * top-1/top-4 hit rates + share).
 *
 * Log lines are schema-compatible with gesturelab's swipes.jsonl (points / top4
 * fields), plus "source":"trainer", target/committed and per-scorer hit booleans,
 * so corpora can be merged for offline tuning.
 */
class SwipeTrainerActivity : Activity() {

    // ---- session state ----
    private class Attempt(
        val target: String,
        val committed: String,
        val committedCorrect: Boolean,
        /** scorer -> (top1Hit, top4Hit) */
        val hits: Map<String, Pair<Boolean, Boolean>>,
    )

    private var words: List<String> = DEFAULT_WORDS
    private var reps = 5
    private var queue: MutableList<String> = mutableListOf()
    private var queueIndex = 0
    private val attempts = ArrayList<Attempt>()
    private val sessionJsonLines = ArrayList<String>()
    private var lastConsumedRecordId = 0L
    private var evaluating = false

    // ---- views ----
    private lateinit var root: LinearLayout
    private lateinit var targetView: TextView
    private lateinit var feedbackView: TextView
    private lateinit var progressView: TextView
    private lateinit var inputField: EditText

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showConfig()
    }

    // ---- phase 1: config ----

    private fun showConfig() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        layout.addView(TextView(this).apply {
            text = "Swipe Trainer\n\nSwipe each word with the HeliBoard Lab keyboard. Edit the word list (whitespace-separated) and reps per word."
        })
        val wordsField = EditText(this).apply {
            setText(DEFAULT_WORDS.joinToString(" "))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
        }
        layout.addView(wordsField)
        val repsField = EditText(this).apply {
            setText("5")
            hint = "reps per word"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(repsField)
        layout.addView(Button(this).apply {
            text = "Start session"
            setOnClickListener {
                words = wordsField.text.toString().trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
                reps = repsField.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 5
                if (words.isEmpty()) {
                    Toast.makeText(this@SwipeTrainerActivity, "no words", Toast.LENGTH_SHORT).show()
                } else startSession()
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ---- phase 2: run ----

    private fun startSession() {
        attempts.clear()
        sessionJsonLines.clear()
        queue = words.flatMap { w -> List(reps) { w } }.toMutableList()
        queueIndex = 0
        lastConsumedRecordId = LastDecodeHolder.latest?.id ?: 0L

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        progressView = TextView(this)
        root.addView(progressView)
        targetView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(targetView)
        feedbackView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            text = " "
        }
        root.addView(feedbackView)
        inputField = EditText(this).apply {
            hint = "swipe here with HeliBoard Lab"
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onInputChanged(s?.toString().orEmpty()) }
            })
        }
        root.addView(inputField)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Skip word"
            setOnClickListener { skipCurrentWord() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "End session"
            setOnClickListener { showSummary() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)
        updateRunViews()
        inputField.requestFocus()
    }

    private fun currentTarget(): String? = queue.getOrNull(queueIndex)

    private fun updateRunViews() {
        val target = currentTarget()
        if (target == null) {
            showSummary()
            return
        }
        val wordIndex = queueIndex / reps
        val rep = queueIndex % reps
        progressView.text = "word ${wordIndex + 1}/${words.size} · rep ${rep + 1}/$reps · ${attempts.size} recorded"
        targetView.text = target
    }

    /** Counts a swipe only when a FRESH decode record exists (ignores tap-typing). */
    private fun onInputChanged(text: String) {
        if (evaluating) return
        val committed = text.trim()
        if (committed.isEmpty()) return
        val target = currentTarget() ?: return
        val record = LastDecodeHolder.latest
        if (record == null || record.id <= lastConsumedRecordId) return // not from a swipe
        lastConsumedRecordId = record.id
        evaluating = true

        val correct = committed.equals(target, ignoreCase = true)
        val hits = record.perScorerTop4.mapValues { (_, top4) ->
            Pair(
                top4.firstOrNull()?.word.equals(target, ignoreCase = true),
                top4.take(4).any { it.word.equals(target, ignoreCase = true) },
            )
        }
        attempts.add(Attempt(target, committed, correct, hits))
        logAttempt(target, committed, correct, record, hits)

        feedbackView.text = if (correct) "✓ $committed" else "✗ $committed (wanted $target)"
        feedbackView.setTextColor(if (correct) Color.rgb(0, 140, 0) else Color.rgb(190, 0, 0))

        inputField.postDelayed({
            inputField.setText("")
            queueIndex++
            evaluating = false
            updateRunViews()
        }, 450)
    }

    private fun skipCurrentWord() {
        val target = currentTarget() ?: return
        // jump to the first queue entry that is not this word anymore
        while (currentTarget() == target) queueIndex++
        inputField.setText("")
        feedbackView.text = "skipped $target"
        feedbackView.setTextColor(Color.GRAY)
        updateRunViews()
    }

    // ---- logging ----

    private fun logFile(): File = File(getExternalFilesDir(null) ?: filesDir, "swipes.jsonl")

    private fun logAttempt(
        target: String, committed: String, correct: Boolean,
        record: LastDecodeRecord, hits: Map<String, Pair<Boolean, Boolean>>,
    ) {
        try {
            val obj = JSONObject()
            obj.put("time", record.timeMillis)
            obj.put("source", "trainer")
            obj.put("intended", target)
            obj.put("committed", committed)
            obj.put("committedCorrect", correct)
            obj.put("activeScorer", record.activeScorer)
            obj.put("locale", record.localeTag)
            val pts = JSONArray()
            for (p in record.points) pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.t))
            obj.put("points", pts)
            val top4 = JSONObject()
            for ((scorer, list) in record.perScorerTop4) top4.put(scorer, scoredWordsJson(list))
            obj.put("top4", top4)
            val hitsJson = JSONObject()
            for ((scorer, hit) in hits) {
                hitsJson.put(scorer, JSONObject().put("top1", hit.first).put("top4", hit.second))
            }
            obj.put("hits", hitsJson)
            val line = obj.toString()
            sessionJsonLines.add(line)
            logFile().appendText(line + "\n")
        } catch (t: Throwable) {
            Toast.makeText(this, "log failed: $t", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scoredWordsJson(list: List<ScoredWord>): JSONArray {
        val arr = JSONArray()
        for (w in list.take(4)) {
            arr.put(JSONObject().put("word", w.word).put("score", w.score.toDouble()).put("raw", w.rawScore.toDouble()))
        }
        return arr
    }

    // ---- phase 3: summary ----

    private fun showSummary() {
        val scorerNames = attempts.flatMap { it.hits.keys }.distinct().sorted()
        val summary = buildSummaryText(scorerNames)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val summaryView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = summary
            setHorizontallyScrolling(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(summaryView)
        layout.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener { share(summary) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "New session"
            setOnClickListener { showConfig() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(buttons)
        layout.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "log: ${logFile().absolutePath}"
        })
        setContentView(layout)
    }

    private fun buildSummaryText(scorerNames: List<String>): String {
        val sb = StringBuilder()
        sb.append("Swipe Trainer session — ${attempts.size} swipes\n")
        sb.append("(cells: top1/top4 hits out of n)\n\n")
        val col = 12
        sb.append("word".padEnd(col))
        for (s in scorerNames) sb.append(s.padEnd(col))
        sb.append("committed".padEnd(col)).append('\n')
        sb.append("-".repeat(col * (scorerNames.size + 2))).append('\n')
        for (word in words) {
            val wordAttempts = attempts.filter { it.target == word }
            if (wordAttempts.isEmpty()) continue
            val n = wordAttempts.size
            sb.append(word.padEnd(col))
            for (s in scorerNames) {
                val t1 = wordAttempts.count { it.hits[s]?.first == true }
                val t4 = wordAttempts.count { it.hits[s]?.second == true }
                sb.append("$t1/$t4 of $n".padEnd(col))
            }
            sb.append("${wordAttempts.count { it.committedCorrect }}/$n".padEnd(col)).append('\n')
        }
        sb.append('\n').append("TOTAL".padEnd(col))
        val n = attempts.size
        for (s in scorerNames) {
            val t1 = attempts.count { it.hits[s]?.first == true }
            val t4 = attempts.count { it.hits[s]?.second == true }
            val pct1 = if (n == 0) 0.0 else 100.0 * t1 / n
            val pct4 = if (n == 0) 0.0 else 100.0 * t4 / n
            sb.append(String.format("%.0f%%/%.0f%%", pct1, pct4).padEnd(col))
        }
        sb.append("${attempts.count { it.committedCorrect }}/$n".padEnd(col)).append('\n')
        return sb.toString()
    }

    private fun share(summary: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Swipe Trainer session")
            putExtra(Intent.EXTRA_TEXT, summary + "\n\nJSONL (${sessionJsonLines.size} lines):\n"
                    + sessionJsonLines.joinToString("\n"))
        }
        startActivity(Intent.createChooser(intent, "Share session"))
        Toast.makeText(this, logFile().absolutePath, Toast.LENGTH_LONG).show()
    }

    companion object {
        // categories from the M1 accuracy harness: short, double-letter, adjacent-key pairs, long
        private val DEFAULT_WORDS = listOf(
            "is", "in", "on", "it", "at",
            "loop", "ball", "been", "look", "school", "too",
            "world", "would", "form", "from", "then", "than", "hot", "hit",
            "something", "keyboard", "question", "important", "different",
        )
    }
}
