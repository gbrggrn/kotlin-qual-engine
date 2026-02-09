package com.qualengine.core.analysis

object TextSanitizer {
    // Matches any character that is NOT:
    // - A letter or digit
    // - Standard punctuation
    // - Whitespace (space, tab, newline)
    // - Common currency/math symbols
    // Effectively strips invisible control codes (like \x02, \x00, \x1F)
    private val CONTROL_CHAR_REGEX = Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}\\p{S}]")

    fun sanitize(input: String): String {
        // 1. Remove control characters
        var clean = input.replace(CONTROL_CHAR_REGEX, "")

        // 2. Collapse multiple spaces into one
        clean = clean.replace(Regex("\\s+"), " ")

        return clean.trim()
    }
}