package com.qualengine.model

import java.util.regex.Pattern

object Moleculizer {

    // Hopefully cross-platform
    private val PARAGRAPH_SPLITTER = Pattern.compile("""(\r?\n\s*){2,}""")

    fun moleculize(docId: String, text: String): List<ParagraphMolecule> {
        if (text.isBlank()) return emptyList()

        // This forces a cut whenever it sees punctuation followed by space.
        val rawParagraphs = text.split(PARAGRAPH_SPLITTER.toRegex())

        val molecules = mutableListOf<ParagraphMolecule>()
        var orderIndex = 0

        for (paragraph in rawParagraphs) {
            val cleanText = paragraph.trim()

            if (cleanText.length > 5000) {
                println("[MOLECULIZER]: WARNING: Parahraph too large (${cleanText.length} chars). Skipping to avoid crash...")
            }

            // We ignore empty strings or things shorter than 3 chars (like "A.")
            if (cleanText.isNotEmpty() && cleanText.length > 3) {
                molecules.add(
                    ParagraphMolecule(
                        docId = docId,
                        content = cleanText,
                        index = orderIndex++
                    )
                )
            }
        }

        return molecules
    }
}