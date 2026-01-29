package com.qualengine.model

import java.util.regex.Pattern

object Atomizer {

    // Regex Explanation:
    // (?<=[.!?])  -> Look behind for a period, exclamation, or question mark
    // \s+         -> followed by one or more spaces/newlines
    private val SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+")

    fun atomize(docId: String, text: String): List<SentenceAtom> {
        if (text.isBlank()) return emptyList()

        // This forces a cut whenever it sees punctuation followed by space.
        val rawSentences = text.split(SPLIT_PATTERN.toRegex())

        val atoms = mutableListOf<SentenceAtom>()
        var orderIndex = 0

        for (sentence in rawSentences) {
            val cleanText = sentence.trim()

            // We ignore empty strings or things shorter than 3 chars (like "A.")
            if (cleanText.isNotEmpty() && cleanText.length > 3) {
                atoms.add(
                    SentenceAtom(
                        docId = docId,
                        content = cleanText,
                        index = orderIndex++
                    )
                )
            }
        }

        return atoms
    }
}