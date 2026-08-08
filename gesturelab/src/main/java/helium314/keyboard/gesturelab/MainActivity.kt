// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesturelab

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import helium314.keyboard.gesture.GestureDecoder
import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.GesturePreprocessor
import helium314.keyboard.gesture.HybridScorer
import helium314.keyboard.gesture.InflectionType
import helium314.keyboard.gesture.KushlerScorer
import helium314.keyboard.gesture.Scorer
import helium314.keyboard.gesture.ScoredWord
import helium314.keyboard.gesture.Shark2Scorer
import helium314.keyboard.gesture.Vocabulary

/**
 * Gesture Lab: swipe on the rendered qwerty; the last swipe's raw path, resampled
 * path and classified inflection points are overlaid, and the top-4 candidates of
 * all three scorers (Kushler / SHARK² / Hybrid) are shown side by side.
 * Toggle REC to append swipes (with the intended word from the text field) to a
 * JSONL corpus; Share exports it via ACTION_SEND.
 */
class MainActivity : Activity() {

    private lateinit var keyboardView: KeyboardSwipeView
    private lateinit var resultsView: TextView
    private lateinit var wordField: EditText
    private lateinit var recordToggle: ToggleButton

    private val preprocessor = GesturePreprocessor()
    private val scorers: List<Scorer> = listOf(KushlerScorer(), Shark2Scorer(), HybridScorer())
    private var vocabulary: Vocabulary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vocabulary = LabVocabulary.load(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (8 * resources.displayMetrics.density).toInt()

        // top bar: intended word + record toggle + share
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        wordField = EditText(this).apply { hint = "intended word (for recording)" }
        topBar.addView(wordField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        recordToggle = ToggleButton(this).apply {
            textOn = "REC ●"
            textOff = "rec"
            text = textOff
        }
        topBar.addView(recordToggle)
        val shareButton = Button(this).apply {
            text = "Share"
            setOnClickListener { shareLog() }
        }
        topBar.addView(shareButton)
        root.addView(topBar)

        // results panel (scrollable, monospace three-column table)
        resultsView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad, pad, pad, pad)
            text = "Swipe a word on the keyboard below.\n\nColumns: Kushler | SHARK² | Hybrid\n(score in parentheses, lower is better)"
        }
        val scroll = ScrollView(this)
        scroll.addView(resultsView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // legend for inflection-point colors
        root.addView(buildLegend(pad))

        // keyboard at the bottom
        keyboardView = KeyboardSwipeView(this).apply { onSwipe = { onSwipe(it) } }
        root.addView(keyboardView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        setContentView(root)
    }

    private fun buildLegend(pad: Int): TextView {
        val sb = SpannableStringBuilder()
        val entries = listOf(
            InflectionType.PEN_DOWN to "down",
            InflectionType.PEN_UP to "up",
            InflectionType.ANGLE_THRESHOLD to "angle",
            InflectionType.PAUSE to "pause",
            InflectionType.ROW_CHANGE to "row",
            InflectionType.DOUBLE_LETTER to "double",
        )
        for ((type, label) in entries) {
            val start = sb.length
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(KeyboardSwipeView.inflectionColor(type)), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" $label   ")
        }
        sb.append("· raw path blue · caps excursion pink · resampled teal dots")
        return TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(pad, 0, pad, pad / 2)
            text = sb
        }
    }

    private fun onSwipe(points: List<GesturePoint>) {
        val vocab = vocabulary ?: return
        val geometry = keyboardView.geometry
        val preprocessed = preprocessor.preprocess(points, geometry)
        keyboardView.showOverlay(points, preprocessed)

        // decode with all three scorers so disagreement is visible per swipe
        val results = LinkedHashMap<String, List<ScoredWord>>()
        for (scorer in scorers) {
            results[scorer.name] = GestureDecoder(scorer).decode(points, geometry, vocab, 4)
        }
        resultsView.text = formatResults(results, preprocessed.inflections.size)

        if (recordToggle.isChecked) {
            val file = SwipeLogger.append(this, wordField.text.toString().trim(), points, results)
            Toast.makeText(this, "logged → ${file.absolutePath}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatResults(results: Map<String, List<ScoredWord>>, inflectionCount: Int): CharSequence {
        val cols = results.keys.toList()
        val width = 16
        val sb = StringBuilder()
        sb.append(cols.joinToString("") { it.padEnd(width) }).append('\n')
        sb.append("-".repeat(width * cols.size)).append('\n')
        for (rank in 0 until 4) {
            for (col in cols) {
                val r = results[col]?.getOrNull(rank)
                val cell = if (r == null) "" else "${r.word} (${"%.2f".format(r.score)})"
                sb.append(cell.padEnd(width))
            }
            sb.append('\n')
        }
        sb.append("\ninflection points: $inflectionCount")
        return sb.toString()
    }

    private fun shareLog() {
        val file = SwipeLogger.logFile(this)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "no swipes recorded yet (${file.absolutePath})", Toast.LENGTH_LONG).show()
            return
        }
        // Plain-text ACTION_SEND keeps this dependency-free (no FileProvider needed);
        // the file itself can also be pulled via adb from the path shown below.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "gesturelab swipes.jsonl")
            putExtra(Intent.EXTRA_TEXT, file.readText())
        }
        startActivity(Intent.createChooser(intent, "Export swipe corpus"))
        Toast.makeText(this, file.absolutePath, Toast.LENGTH_LONG).show()
    }
}
