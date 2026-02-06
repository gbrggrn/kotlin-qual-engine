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

        // --- Statistical fingerprint
        val total = trimmed.length
        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }
        val whitespace = trimmed.count { it.isWhitespace() }
        val symbols = total - whitespace - digits - letters // Symbols are what's left...

        val letterRatio = letters.toDouble() / total
        val symbolRatio = symbols.toDouble() / total

        return when {
            // High symbol density -> probably noise
            symbolRatio > 0.20 -> SanityStatus.NOISE
            // Low letter ratio -> needs human review
            letterRatio > 0.20 -> SanityStatus.QUARANTINE
            // Assume the rest are clean
            else -> SanityStatus.CLEAN
        }
    }
}

    /*
    fun evaluate(text: String): SanityStatus {
        val trimmed = text.trim()
        if (trimmed.length < 5)
            return SanityStatus.NOISE

        // Statistical fingerprint
        val total = trimmed.length
        val letters = trimmed.count { it.isLetter() }
        val numbers = trimmed.count { it.isDigit() }
        val whitespace = trimmed.count { it.isWhitespace() }
        val symbols = total - letters - numbers - whitespace

        // Calculate weights
        val letterRatio = letters.toDouble() / total
        val symbolRatio = symbols.toDouble() / total

        return when {
            // --- Rule 1: If 20% is symbols it's probably noise
            symbolRatio > 0.20 -> SanityStatus.NOISE
            // --- Rule 2: If there are too few letters, there is probably little meaning
            letterRatio < 0.50 -> SanityStatus.QUARANTINE
            // --- Rule 3: Test check for metadata TODO: Does this work??
            total < 50 && trimmed.contains(":") -> SanityStatus.QUARANTINE
            else -> SanityStatus.CLEAN
        }
    }
    /*fun evaluate(text: String): SanityStatus {
        val trimmed = text.trim()

        // Sort out noise TODO: Extend this?
        if (trimmed.matches(Regex("^[\\-_=]{3,}$"))) {
            println("[SANITY_STATUS]: Quarantined $trimmed. Numbers/Symbols")
            return SanityStatus.NOISE
        }
        if (trimmed.isEmpty()){
            println("[SANITY_STATUS]: Quarantined $trimmed. Empty")
            return SanityStatus.NOISE
        }

        // Simple metaData-filter TODO: Make this a proper filter
        val isMetaData = trimmed.length < 45 && (trimmed.contains(":") || trimmed.contains("Ticket #"))

        // Calculate alphanumeric density (substance check)
        val letterCount = trimmed.count { it.isLetter() }
        val density = if (trimmed.isNotEmpty()){
            letterCount.toDouble() / trimmed.length
        } else {
            println("[SANITY_STATUS]: Density too low: letter count: $letterCount")
            0.0
        }

        return when {
            density < 0.25 -> SanityStatus.NOISE  // Mostly symbols & numbers
            isMetaData -> SanityStatus.QUARANTINE // Useful context but not a theme
            trimmed.split(" ").size < 3 -> SanityStatus.QUARANTINE // Too short to be thought
            else -> SanityStatus.CLEAN // Useful data
        }
    }*/