package com.qualengine.core.analysis

enum class SanityStatus {
    CLEAN,          // Post-it-worthy (high value)
    QUARANTINE,     // Needs human review (low value)
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

    // Settings
    private const val MIN_SENTENCE_LENGTH = 5;
    private const val MAX_ARTIFACT_PATTERN_LENGTH = 100;
    private const val MAX_ACADEMIC_NOISE_LENGTH = 300;
    private const val MAX_CAPTION_PATTERN_LENGTH = 150;
    private const val MAX_HEADER_PATTERN_LENGTH = 80;
    private const val MIN_SYMBOL_DENSITY = 0.15;
    private const val MAX_LETTER_RATIO = 0.50;

    // ======================================================================================
    // Checks the text against different regexes to remove different types of noise/symbols.
    // ======================================================================================
    fun evaluate(text: String): SanityStatus {
        val trimmed = text.trim()

        // === Too short to be a thematic sentence
        if (trimmed.length < MIN_SENTENCE_LENGTH)
            return SanityStatus.NOISE

        // === Check against the regex artifact patterns
        if (trimmed.length < MAX_ARTIFACT_PATTERN_LENGTH && ARTIFACT_PATTERNS.any { trimmed.contains(it) }) {
            return SanityStatus.NOISE
        }

        // === Check against academic noise regex
        if (text.length < MAX_ACADEMIC_NOISE_LENGTH && ACADEMIC_NOISE.any { trimmed.contains(it) }) {
            return SanityStatus.NOISE
        }

        // === Check against caption pattern regex
        if (text.length < MAX_CAPTION_PATTERN_LENGTH && CAPTION_PATTERN.containsMatchIn(text)) {
            return SanityStatus.NOISE
        }

        // === Check against the regex for header patterns
        if (trimmed.length < MAX_HEADER_PATTERN_LENGTH) {
            val startsWithNumber = HEADER_NUMBERING.containsMatchIn(trimmed)
            val endsWithPunctuation = trimmed.last() in setOf(".", "!", '?')

            if (startsWithNumber || !endsWithPunctuation) {
                return SanityStatus.NOISE
            }
        }

        // === Statistical fingerprint
        val total = trimmed.length.toDouble()
        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }
        val whitespace = trimmed.count { it.isWhitespace() }
        val symbols = total - whitespace - digits - letters // Symbols are what's left...

        val letterRatio = letters.toDouble() / total
        val symbolRatio = symbols / total

        return when {
            // High symbol density -> probably noise
            symbolRatio > MIN_SYMBOL_DENSITY -> SanityStatus.NOISE
            // Low letter ratio (perfect would maybe be 0.80) -> needs human review
            letterRatio < MAX_LETTER_RATIO -> SanityStatus.QUARANTINE
            // Assume the rest are clean
            else -> SanityStatus.CLEAN
        }
    }
}