// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import android.os.SystemClock
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.gesture.Vocabulary
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-locale [Vocabulary] for the in-tree gesture decoder (lab flavor).
 *
 * Built asynchronously from the ACTUAL dictionaries of the locale: the top
 * [MAX_WORDS] words by probability from the main binary dictionary (the same
 * cached/extracted .dict file the facilitator loads — opened read-only via our
 * own [BinaryDictionary], the pattern GestureDataScreen already uses), merged
 * with user-history words at boosted weight.
 *
 * Decoding never blocks on this: [getOrBuildAsync] returns null until the build
 * completes (the decoder then simply produces no results yet).
 */
object GestureDecoderVocabulary {
    private const val TAG = "GestureDecoderVocab"
    private const val MAX_WORDS = 10_000
    private const val MIN_PROBABILITY = 1 // dictionary probabilities are 0..255, log-ish
    private const val USER_HISTORY_BOOST = 64

    private val cache = ConcurrentHashMap<String, Vocabulary>()
    private val building = ConcurrentHashMap.newKeySet<String>()

    /** Cached vocabulary for [locale], or null (and an async build is kicked off). */
    fun getOrBuildAsync(locale: Locale): Vocabulary? {
        val key = locale.toLanguageTag()
        cache[key]?.let { return it }
        if (building.add(key)) {
            Thread({
                try {
                    val vocab = build(locale)
                    if (vocab != null && vocab.size > 0) cache[key] = vocab
                } catch (t: Throwable) {
                    Log.w(TAG, "vocabulary build failed for $key", t)
                } finally {
                    building.remove(key)
                }
            }, "GestureVocabBuild-$key").start()
        }
        return null
    }

    /** Drop cached vocabularies (e.g. after dictionary changes). */
    fun clear() = cache.clear()

    private fun build(locale: Locale): Vocabulary? {
        val context = Settings.getCurrentContext() ?: return null
        val start = SystemClock.elapsedRealtime()
        val words = HashMap<String, Int>(MAX_WORDS * 2)

        val mainDictFile = findMainDictFile(context, locale)
        if (mainDictFile != null) {
            addWordsFromDict(mainDictFile, locale, words)
        } else {
            Log.w(TAG, "no main dictionary file found for $locale")
        }

        // merge user history with boosted weight (these are words the user actually types)
        try {
            val history = PersonalizationHelper.getUserHistoryDictionary(context, locale)
            for (wp in history.wordPropertiesForSyncing) {
                val word = wp.mWord ?: continue
                if (!isDecodableWord(word)) continue
                val boosted = (wp.probability + USER_HISTORY_BOOST).coerceIn(1, 255)
                words.merge(word, boosted) { a, b -> maxOf(a, b) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not merge user history for $locale", t)
        }

        if (words.isEmpty()) return null
        val top = words.entries.sortedByDescending { it.value }.take(MAX_WORDS)
        val vocab = Vocabulary(top.map { it.key to it.value })
        Log.d(TAG, "built vocabulary for $locale: ${vocab.size} words (of ${words.size} collected) " +
                "in ${SystemClock.elapsedRealtime() - start} ms")
        return vocab
    }

    /**
     * The main dictionary file the keyboard uses for [locale]: prefer a user-provided
     * dict in the locale's cache dir, then the extracted internal one, else extract the
     * bundled assets dictionary.
     */
    private fun findMainDictFile(context: Context, locale: Locale): File? {
        val cached = DictionaryInfoUtils.getCachedDictsForLocale(locale, context)
            .filter { it.name.startsWith(DictionaryInfoUtils.DEFAULT_MAIN_DICT) && it.name.endsWith(".dict") }
        cached.firstOrNull { it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }?.let { return it }
        cached.firstOrNull()?.let { return it }
        // not cached yet: extract the assets dict for this language, like DictionaryFactory does
        val assetsDicts = DictionaryInfoUtils.getAssetsDictionaryList(context).orEmpty()
        val match = assetsDicts.firstOrNull {
            it.startsWith(DictionaryInfoUtils.DEFAULT_MAIN_DICT) &&
                    it.substringAfter("_").substringBefore(".dict").constructLocale().language == locale.language
        } ?: return null
        return DictionaryInfoUtils.extractAssetsDictionary(match, locale, context)
    }

    private fun addWordsFromDict(file: File, locale: Locale, words: MutableMap<String, Int>) {
        val blockOffensive = try { Settings.getValues()?.mBlockPotentiallyOffensive ?: true } catch (_: Throwable) { true }
        val dict = BinaryDictionary(file.absolutePath, 0, file.length(), false, locale, Dictionary.TYPE_MAIN, false)
        try {
            if (!dict.isValidDictionary) return
            var token = 0
            do {
                val result = dict.getNextWordProperty(token)
                val wp = result.mWordProperty ?: break
                val word = wp.mWord
                if (word != null && !wp.mIsNotAWord
                    && wp.probability >= MIN_PROBABILITY
                    && !(wp.mIsPossiblyOffensive && blockOffensive)
                    && isDecodableWord(word)
                ) {
                    words.merge(word, wp.probability.coerceAtMost(255)) { a, b -> maxOf(a, b) }
                }
                token = result.mNextToken
            } while (token != 0)
        } finally {
            dict.close()
        }
    }

    /**
     * Only words the sokgraph builder can route over keys are useful in the trie:
     * letters, plus apostrophes (gestured via the period key, OG-Swype style).
     * Hyphenated words stay excluded for now.
     */
    private fun isDecodableWord(word: String): Boolean =
        word.length in 1..24
                && word.all { Character.isLetter(it) || it == '\'' || it == '’' }
                && word.any { Character.isLetter(it) }
}
