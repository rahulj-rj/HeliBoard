// SPDX-License-Identifier: GPL-3.0-only
// Trie vocabulary for the gesture decoder, populated from the binary dictionary /
// user history. Children are stored as parallel arrays (char + node) instead of a
// HashMap: at 50k words the trie has ~150k nodes, and boxed-char hash maps cost
// several times the memory and iterate slower in the decoder's hot trie walk.
package helium314.keyboard.gesture

/** Trie of (word, frequency). Frequencies must be positive. */
class Vocabulary(entries: Iterable<Pair<String, Int>>) {

    class Node {
        var word: String? = null
            internal set
        var frequency: Int = 0
            internal set
        private var childChars = EMPTY_CHARS
        private var childNodes = EMPTY_NODES

        val childCount: Int get() = childChars.size
        fun childCharAt(i: Int): Char = childChars[i]
        fun childAt(i: Int): Node = childNodes[i]!!

        fun child(c: Char): Node? {
            val chars = childChars
            for (i in chars.indices) if (chars[i] == c) return childNodes[i]
            return null
        }

        internal fun getOrPut(c: Char): Node {
            child(c)?.let { return it }
            val node = Node()
            val n = childChars.size
            childChars = childChars.copyOf(n + 1).also { it[n] = c }
            childNodes = childNodes.copyOf(n + 1).also { it[n] = node }
            return node
        }

        companion object {
            private val EMPTY_CHARS = CharArray(0)
            private val EMPTY_NODES = arrayOfNulls<Node>(0)
        }
    }

    val root = Node()
    var maxFrequency: Int = 1
        private set
    var size: Int = 0
        private set

    init {
        for ((word, freq) in entries) add(word, freq)
    }

    fun add(word: String, frequency: Int) {
        if (word.isEmpty() || frequency <= 0) return
        var node = root
        for (c in word) node = node.getOrPut(c.lowercaseChar())
        if (node.word == null) size++
        // keep the casing of the highest-frequency variant (trie keys are lowercased,
        // stored words keep original casing so e.g. proper nouns display correctly)
        if (frequency >= node.frequency) {
            node.word = word
            node.frequency = frequency
        }
        if (frequency > maxFrequency) maxFrequency = frequency
    }

    fun contains(word: String): Boolean = find(word)?.word != null

    fun frequencyOf(word: String): Int = find(word)?.frequency ?: 0

    private fun find(word: String): Node? {
        var node = root
        for (c in word) node = node.child(c.lowercaseChar()) ?: return null
        return node
    }
}
