package com.qualengine.core.analysis

enum class SanityStatus {
    CLEAN,          // Post-it-worthy (high value)
    QUARANTINE,     // Whatever-bucket (low value)
    NOISE           // Structural crap (no value)
}

object SanityFilter {

    fun evaluate(text: String): SanityStatus {
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
    }
}