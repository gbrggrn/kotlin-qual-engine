package com.qualengine.core.analysis

import kotlin.math.ln

object LabelGenerator {

    // Common English words to ignore TODO: Extend this list???
    private val STOP_WORDS = setOf(
        "the", "and", "is", "of", "to", "in", "a", "for", "with", "that", "this", "on", "as",
        "are", "was", "by", "an", "be", "or", "at", "from", "we", "can", "it", "has", "but",
        "program", "project", "system", "data", "result", "analysis", "using", "based"
    )

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