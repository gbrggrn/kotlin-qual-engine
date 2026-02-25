package com.qualengine.core.analysis

import kotlin.math.ln

object LabelGenerator {

    // Common English words to ignore TODO: Extend this list???
    private val STOP_WORDS = setOf(
        // NLTK stop words
        "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "you're", "you've",
        "you'll", "you'd", "your", "yours", "yourself", "yourselves", "he", "him", "his",
        "himself", "she", "she's", "her", "hers", "herself", "it", "it's", "its", "itself",
        "they", "them", "their", "theirs", "themselves", "what", "which", "who", "whom", "this",
        "that", "that'll", "these", "those", "am", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having", "do", "does", "did", "doing", "a", "an", "the", "and",
        "but", "if", "or", "because", "as", "until", "while", "of", "at", "by", "for", "with",
        "about", "against", "between", "into", "through", "during", "before", "after", "above",
        "below", "to", "from", "up", "down", "in", "out", "on", "off", "over", "under", "again",
        "further", "then", "once", "here", "there", "when", "where", "why", "how", "all", "any",
        "both", "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only",
        "own", "same", "so", "than", "too", "very", "s", "t", "can", "will", "just", "don", "don't",
        "should", "should've", "now", "d", "ll", "m", "o", "re", "ve", "y", "ain", "aren", "aren't",
        "couldn", "couldn't", "didn", "didn't", "doesn", "doesn't", "hadn", "hadn't", "hasn", "hasn't",
        "haven", "haven't", "isn", "isn't", "ma", "mightn", "mightn't", "mustn", "mustn't", "needn",
        "needn't", "shan", "shan't", "shouldn", "shouldn't", "wasn", "wasn't", "weren", "weren't",
        "won", "won't", "wouldn", "wouldn't"
    )

    // ===============================
    // Calculates the frequencies of words in the target-, and background texts.
    // Picks the 3 most frequent "meaningful" words that are dissimilar to the background.
    // ===============================
    fun generateLabel(targetTexts: List<String>, backgroundTexts: List<String>): String {
        if (targetTexts.isEmpty())
            return "Unknown"

        // Tokenize
        val targetTokens = tokenize(targetTexts)
        val backgroundTokens = tokenize(backgroundTexts)

        if (targetTokens.isEmpty())
            return "Cluster"

        // Count frequencies of words
        val targetCounts = targetTokens.groupingBy { it }.eachCount()
        val backgroundCounts = backgroundTokens.groupingBy { it }.eachCount()

        // Calculate c-TF-IDF score
        // Frequency in cluster * log1(1 + Total / Frequency in background)
        val scores = targetCounts.mapValues { (word, tf) ->
            val bgTf = backgroundCounts[word] ?: 0
            // Punish words that appear often in background
            val idf = ln(1.0 + (backgroundTokens.size.toDouble() / (bgTf + 1.0)))
            tf * idf
        }

        // Select top words
        return scores.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(" ") { it.key.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun tokenize(texts: List<String>): List<String> {
        return texts.flatMap { text ->
            text.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 && it !in STOP_WORDS }
        }
    }
}