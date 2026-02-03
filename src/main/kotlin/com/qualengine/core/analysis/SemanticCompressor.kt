package com.qualengine.core.analysis

object SemanticCompressor {
    private val STOP_WORDS = setOf (
        // 1. Articles & Determinates
        "the", "a", "an", "this", "that", "these", "those", "some", "any", "all", "each", "every",

        // 2. Prepositions (Space/Time)
        "in", "on", "at", "to", "for", "from", "with", "by", "about", "into", "over", "after", "before",
        "under", "between", "out", "through", "during", "without", "within", "where", "when", "why", "how",

        // 3. Conjunctions
        "and", "or", "but", "if", "because", "as", "until", "while", "although", "unless", "since", "so", "than",

        // 4. Verbs of Being/Having (The "State" Glue)
        "is", "are", "was", "were", "am", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did", "doing",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must", "ought",

        // 5. Pronouns (Since we prioritize Named Entities, these are usually vague)
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "mine", "yours", "theirs", "myself", "yourself", "themselves",

        // 6. Business Filler & Hedge Words (The "Qualitative Fluff")
        "just", "only", "very", "really", "quite", "rather", "almost", "literally", "basically", "actually",
        "essentially", "generally", "typically", "mostly", "mainly", "simply", "possibly", "probably", "perhaps",
        "seem", "seemed", "seems", "like", "sort", "kind", "type", "bit", "lot", "stuff", "things",
        "regarding", "concerning", "according", "relates", "related", "various", "several"
    )

    fun compress(text: String): String {
        if (text.isBlank())
            return ""

        val words = text.split(Regex("\\s+"))

        val signals = words.filter { rawWord ->
            val w = rawWord.filter { it.isLetterOrDigit() }

            when {
                w.isBlank() -> false
                // ALWAYS KEEP NUMBERS
                w.any { it.isDigit() } -> true
                // ALWAYS KEEP CAPITALIZED WORDS
                w.first().isUpperCase() && w.length > 1 -> true
                // ALWAYS KEEP TECHNICAL/LONG WORDS
                w.length < 6 -> true
                // DISCARD STOP_WORDS
                STOP_WORDS.contains(w.lowercase()) -> false
                else -> true
            }
        }

        val compressed = signals.joinToString(" ")

        return if (compressed.length > 250) {
            val head = compressed.take(150)
            val tail = compressed.takeLast(100)
            "$head ... $tail"
        } else {
            compressed
        }
    }
}