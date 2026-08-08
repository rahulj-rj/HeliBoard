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
 * Built from the ACTUAL dictionaries of the locale: the top [MAX_WORDS] words by
 * probability from the main binary dictionary (the same cached/extracted .dict file
 * the facilitator loads — opened read-only via our own [BinaryDictionary]), merged
 * with user-history words at boosted weight.
 *
 * The main-dict extraction is the expensive part (a full getNextWordProperty walk
 * over JNI, ~16 s at 10k words), so its result is persisted to
 * files/own_gesture_vocab/<languageTag>.txt. On later process starts the list loads
 * from disk in milliseconds and only user history (small, per-user) is merged live;
 * the dictionary is re-walked only when the cache header no longer matches the dict
 * file identity, the word cap, or the offensive-word setting — and while that
 * rebuild runs, the stale-but-close vocabulary already serves swipes.
 *
 * Decoding never blocks on this: [getOrBuildAsync] returns null until a build or
 * disk load completes (the decoder then simply produces no results yet).
 */
object GestureDecoderVocabulary {
    private const val TAG = "GestureDecoderVocab"
    private const val MAX_WORDS = 50_000
    private const val MIN_PROBABILITY = 1 // dictionary probabilities are 0..255, log-ish
    private const val USER_HISTORY_BOOST = 64
    private const val CACHE_DIR = "own_gesture_vocab"
    private const val CACHE_VERSION = 1
    // user-history dict loads asynchronously; on cold start wordPropertiesForSyncing
    // times out internally (100 ms) and returns empty — retry instead of silently
    // dropping the user's personal words from the vocabulary
    private const val HISTORY_RETRIES = 10
    private const val HISTORY_RETRY_DELAY_MS = 200L
    private const val HISTORY_LATE_RETRY_DELAY_MS = 20_000L

    private val cache = ConcurrentHashMap<String, Vocabulary>()
    private val building = ConcurrentHashMap.newKeySet<String>()

    /** Cached vocabulary for [locale], or null (and an async build is kicked off). */
    fun getOrBuildAsync(locale: Locale): Vocabulary? {
        val key = locale.toLanguageTag()
        cache[key]?.let { return it }
        if (building.add(key)) {
            Thread({
                try {
                    loadOrBuild(locale, key)
                } catch (t: Throwable) {
                    Log.w(TAG, "vocabulary build failed for $key", t)
                } finally {
                    building.remove(key)
                }
            }, "GestureVocabBuild-$key").start()
        }
        return null
    }

    /**
     * Drop in-memory vocabularies — call when dictionaries change. The disk cache is
     * deliberately KEPT: its header self-detects a changed main dict (rebuilding in the
     * background while the old list keeps serving swipes), and user history is merged
     * live on every load, so e.g. a backup restore's words appear on the next reload
     * without a dead-swipe window.
     */
    fun clear() = cache.clear()

    private fun loadOrBuild(locale: Locale, key: String) {
        val context = Settings.getCurrentContext() ?: return
        val mainDictFile = findMainDictFile(context, locale)
        val blockOffensive = try { Settings.getValues()?.mBlockPotentiallyOffensive ?: true } catch (_: Throwable) { true }
        val expectedHeader = cacheHeader(mainDictFile, blockOffensive)
        val cacheFile = File(File(context.filesDir, CACHE_DIR), "$key.txt")

        val start = SystemClock.elapsedRealtime()
        val disk = readCache(cacheFile)
        if (disk != null) {
            val merged = publishNow(key, locale, context, disk.entries)
            val loaded = SystemClock.elapsedRealtime() - start
            if (disk.header == expectedHeader) {
                Log.d(TAG, "loaded vocabulary for $key from disk cache in $loaded ms")
                if (merged == 0) retryHistoryLater(key, locale, context, disk.entries)
                return
            }
            Log.d(TAG, "disk cache for $key is stale (serving it anyway), rebuilding: " +
                    "'${disk.header}' vs '$expectedHeader'")
        }

        // full rebuild: the expensive JNI walk over the main dictionary
        val words = HashMap<String, Int>(MAX_WORDS * 2)
        if (mainDictFile != null) {
            addWordsFromDict(mainDictFile, locale, blockOffensive, words)
        } else {
            Log.w(TAG, "no main dictionary file found for $locale")
        }
        if (words.isEmpty()) return // keep whatever the disk cache provided
        val top = words.entries.sortedByDescending { it.value }.take(MAX_WORDS).map { it.key to it.value }
        writeCache(cacheFile, expectedHeader, top)
        val merged = publishNow(key, locale, context, top)
        Log.d(TAG, "built vocabulary for $key: ${top.size} words (of ${words.size} collected) " +
                "in ${SystemClock.elapsedRealtime() - start} ms")
        if (merged == 0) retryHistoryLater(key, locale, context, top)
    }

    /** Build the trie from the main-dict entries, merge live user history, publish it. Returns merged count. */
    private fun publishNow(key: String, locale: Locale, context: Context, mainEntries: List<Pair<String, Int>>): Int {
        val vocab = Vocabulary(mainEntries)
        val merged = mergeUserHistory(context, locale, vocab)
        if (vocab.size > 0) cache[key] = vocab
        return merged
    }

    /**
     * The user-history dictionary loads asynchronously and can still be empty when the
     * vocabulary publishes (observed: 0 words merged after the retry window on a cold
     * start). The published vocab already serves swipes; wait once, well past any
     * plausible dict-load time, then republish with a fresh merge. A genuinely empty
     * history just repeats the no-op merge.
     */
    private fun retryHistoryLater(key: String, locale: Locale, context: Context, mainEntries: List<Pair<String, Int>>) {
        Thread.sleep(HISTORY_LATE_RETRY_DELAY_MS)
        val merged = publishNow(key, locale, context, mainEntries)
        if (merged > 0) Log.d(TAG, "late user-history merge for $key: $merged words")
    }

    /**
     * Merge user-history words at boosted weight (these are words the user actually
     * types). Added AFTER the [MAX_WORDS] cap on main-dict words, so personal words
     * are never evicted by the cap. [Vocabulary.add] keeps the higher frequency, so
     * this is a max-merge.
     */
    private fun mergeUserHistory(context: Context, locale: Locale, vocab: Vocabulary): Int {
        try {
            val history = PersonalizationHelper.getUserHistoryDictionary(context, locale)
            var props = history.wordPropertiesForSyncing
            var attempts = 0
            while (props.isEmpty() && attempts++ < HISTORY_RETRIES) {
                Thread.sleep(HISTORY_RETRY_DELAY_MS)
                props = history.wordPropertiesForSyncing
            }
            var merged = 0
            for (wp in props) {
                val word = wp.mWord ?: continue
                if (!isDecodableWord(word)) continue
                val boosted = (wp.probability + USER_HISTORY_BOOST).coerceIn(1, 255)
                vocab.add(word, boosted)
                merged++
            }
            Log.d(TAG, "merged $merged user-history words for $locale (after $attempts empty reads)")
            return merged
        } catch (t: Throwable) {
            Log.w(TAG, "could not merge user history for $locale", t)
            return 0
        }
    }

    // ---- disk cache ----

    private class DiskCache(val header: String, val entries: List<Pair<String, Int>>)

    /** Identity of the inputs that make a cached word list valid. */
    private fun cacheHeader(dictFile: File?, blockOffensive: Boolean): String =
        if (dictFile == null) "v$CACHE_VERSION|cap=$MAX_WORDS|offensive=$blockOffensive|dict=none"
        else "v$CACHE_VERSION|cap=$MAX_WORDS|offensive=$blockOffensive" +
                "|dict=${dictFile.absolutePath}|size=${dictFile.length()}|mtime=${dictFile.lastModified()}"

    private fun readCache(file: File): DiskCache? {
        if (!file.isFile) return null
        return try {
            file.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext()) return@useLines null
                val header = it.next()
                if (!header.startsWith("v$CACHE_VERSION|")) return@useLines null
                val entries = ArrayList<Pair<String, Int>>(MAX_WORDS)
                while (it.hasNext()) {
                    val line = it.next()
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val freq = line.substring(tab + 1).toIntOrNull() ?: continue
                    entries.add(line.substring(0, tab) to freq)
                }
                if (entries.isEmpty()) null else DiskCache(header, entries)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read vocab cache ${file.name}", t)
            null
        }
    }

    private fun writeCache(file: File, header: String, entries: List<Pair<String, Int>>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(header)
                w.write("\n")
                for ((word, freq) in entries) {
                    w.write(word)
                    w.write("\t")
                    w.write(freq.toString())
                    w.write("\n")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not write vocab cache ${file.name}", t)
        }
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

    private fun addWordsFromDict(file: File, locale: Locale, blockOffensive: Boolean, words: MutableMap<String, Int>) {
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
