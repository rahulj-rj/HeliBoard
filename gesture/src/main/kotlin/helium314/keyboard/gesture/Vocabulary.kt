// SPDX-License-Identifier: GPL-3.0-only
// Trie vocabulary for the gesture decoder. Built from a plain (word, frequency)
// list; in M2 this will be populated from the binary dictionary / user history.
package helium314.keyboard.gesture

/** Trie of (word, frequency). Frequencies must be positive. */
class Vocabulary(entries: Iterable<Pair<String, Int>>) {

    class Node {
        val children = HashMap<Char, Node>()
        var word: String? = null
        var frequency: Int = 0
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
        for (c in word) node = node.children.getOrPut(c.lowercaseChar()) { Node() }
        if (node.word == null) size++
        node.word = word.lowercase()
        node.frequency = maxOf(node.frequency, frequency)
        if (frequency > maxFrequency) maxFrequency = frequency
    }

    fun contains(word: String): Boolean = find(word)?.word != null

    fun frequencyOf(word: String): Int = find(word)?.frequency ?: 0

    private fun find(word: String): Node? {
        var node = root
        for (c in word) node = node.children[c.lowercaseChar()] ?: return null
        return node
    }
}
