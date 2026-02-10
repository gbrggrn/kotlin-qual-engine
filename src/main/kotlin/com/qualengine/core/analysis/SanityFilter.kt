package com.qualengine.core.analysis

enum class SanityStatus {
    CLEAN,          // Post-it-worthy (high value)
    QUARANTINE,     // Whatever-bucket (low value)
    NOISE           // Structural crap (no value)
}

// Regex for common artifacts:
// - "Page 1 of 10"
// - "2023-10-10" (Isolated dates)
// - "v1.0.4"
private val ARTIFACT_PATTERNS = listOf(
    Regex("""(?i)page\s+\d+\s+of\s+\d+"""),
    Regex("""^\d{4}-\d{2}-\d{2}$"""),
    Regex("""^v?\d+(\.\d+)+$""")
)

// Regex for headers specifically
// - "1. Introduction..."
// - "2.3 Analysis..."
private val HEADER_NUMBERING = Regex("""^(\d+(\.\d+)*\.?)\s+""")

// Regex for academic captions
private val CAPTION_PATTERN = Regex("""^(Table|Figure|Fig\.|Chart)\s+\d+[:.]?""")

// Regex for specific academic noise
private val ACADEMIC_NOISE = listOf(
    Regex("""(?i)cc\s+by(-nc)?(-nd)?"""), // Creative Commons
    Regex("""(?i)open\s+access\s+article"""),
    Regex("""(?i)rights\s+reserved"""),
    Regex("""(?i)author\s+reuse\s+guidelines""")
)

object SanityFilter {

    fun evaluate(text: String): SanityStatus {
        val trimmed = text.trim()

        // --- Too short to be a thematic sentence
        if (trimmed.length < 5)
            return SanityStatus.NOISE

        // --- Check against the regex artifact patterns
        if (trimmed.length < 100 && ARTIFACT_PATTERNS.any { trimmed.contains(it) }) {
            return SanityStatus.NOISE
        }

        if (text.length < 300 && ACADEMIC_NOISE.any { trimmed.contains(it) }) {
            return SanityStatus.NOISE
        }

        if (text.length < 150 && CAPTION_PATTERN.containsMatchIn(text)) {
            return SanityStatus.NOISE
        }

        // --- Check against the regex for header patterns
        if (trimmed.length < 80) {
            val startsWithNumber = HEADER_NUMBERING.containsMatchIn(trimmed)
            val endsWithPunctuation = trimmed.last() in setOf(".", "!", '?')

            if (startsWithNumber || !endsWithPunctuation) {
                return SanityStatus.NOISE
            }
        }

        // --- Statistical fingerprint
        val total = trimmed.length.toDouble()
        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }
        val whitespace = trimmed.count { it.isWhitespace() }
        val symbols = total - whitespace - digits - letters // Symbols are what's left...

        val letterRatio = letters.toDouble() / total
        val symbolRatio = symbols / total

        return when {
            // High symbol density -> probably noise
            symbolRatio > 0.15 -> SanityStatus.NOISE
            // Low letter ratio (perfect would maybe be 0.80) -> needs human review
            letterRatio < 0.50 -> SanityStatus.QUARANTINE
            // Assume the rest are clean
            else -> SanityStatus.CLEAN
        }
    }
}