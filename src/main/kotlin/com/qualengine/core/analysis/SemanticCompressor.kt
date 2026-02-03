package com.qualengine.core.analysis

object SemanticCompressor {
    private val STOP_WORDS = setOf (
        // 1. Articles
        "the", "a", "an", "this", "that", "these", "those", "some", "any", "all", "each", "every",

        // 2. Prepositions (space/time)
        "in", "on", "at", "to", "for", "from", "with", "by", "about", "into", "over", "after", "before",
        "under", "between", "out", "through", "during", "without", "within", "where", "when", "why", "how",

        // 3. Conjunctions
        "and", "or", "but", "if", "because", "as", "until", "while", "although", "unless", "since", "so", "than",

        // 4. Verbs of being/having
        "is", "are", "was", "were", "am", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did", "doing",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must", "ought",

        // 5. Pronouns
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "its", "our", "their", "mine", "yours", "theirs", "myself", "yourself", "themselves",

        // 6. Business filler babble
        "just", "only", "very", "really", "quite", "rather", "almost", "literally", "basically", "actually",
        "essentially", "generally", "typically", "mostly", "mainly", "simply", "possibly", "probably", "perhaps",
        "seem", "seemed", "seems", "like", "sort", "kind", "type", "bit", "lot", "stuff", "things",
        "regarding", "concerning", "according", "relates", "related", "various", "several",

        // 7. Niceties
        "please", "thanks", "thank", "thankyou", "hello", "hi", "hey", "dear",
        "greetings", "regards", "sincerely", "best", "cheers",
        "mr", "mrs", "ms", "dr", "sir", "madam",
        "sent", "send", "sending", "write", "writing", "wrote",
        "message", "email", "reply", "attached", "attachment", "finding", "find",

        // 8. Verbs that don't really mean anything
        "get", "gets", "getting", "got", "gotten",
        "go", "goes", "going", "gone", "went",
        "make", "makes", "making", "made",
        "take", "takes", "taking", "took",
        "come", "comes", "coming", "came",
        "know", "knows", "knowing", "knew",
        "think", "thinks", "thinking", "thought",
        "want", "wants", "wanting", "wanted",
        "need", "needs", "needing", "needed",
        "use", "uses", "using", "used",
        "try", "tries", "trying", "tried",
        "look", "looks", "looking", "looked",
        "start", "starts", "starting", "started",

        // 9. Vague times and quantities
        "now", "then", "today", "tomorrow", "yesterday", "soon", "later",
        "always", "never", "sometimes", "often", "usually",
        "day", "days", "week", "weeks", "month", "months", "year", "years",
        "time", "times", "hour", "hours", "minute", "minutes",
        "one", "two", "three", "first", "second", "third", "last", "next",
        "high", "low", "big", "small", "large", "much", "many", "less", "least", "more", "most"
    )

    fun compress(text: String): String {
        if (text.isBlank())
            return ""

        val words = text.split(Regex("\\s+"))

        val signals = words.filter { rawWord ->
            val clean = rawWord.filter { it.isLetterOrDigit() }
            // Return if blank
            if (clean.isBlank())
                return@filter false
            // Make all everything lowercase
            val lower = clean.lowercase()
            // RULE 1: Remove all pre-defined stop-words
            if (STOP_WORDS.contains(lower))
                return@filter false
            // RULE 2: KEEP any digit (like dates, ids, versions...)
            if (clean.any { it.isDigit() })
                return@filter true
            // RULE 3: Keep capitalized words (since stop-words are already gone)
            if (clean.first().isUpperCase())
                return@filter true
            // RULE 4: Remove words shorter than 2 characters since they often provide little context
            if (clean.length > 2)
                return@filter true

            false
        }

        val result = signals.joinToString("")

        return if (result.length > 250) {
            result.take(250) + "..."
        } else {
            result
        }
    }
}