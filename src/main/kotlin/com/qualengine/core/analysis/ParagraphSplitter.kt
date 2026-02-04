package com.qualengine.core.analysis

import com.qualengine.data.model.Paragraph
import java.util.regex.Pattern

// ===============================================================================
// This class is responsible for splitting long strings into a list of paragraphs.
// ===============================================================================
object ParagraphSplitter {

    // Hopefully cross-platform regex
    private val PARAGRAPH_PATTERN = Pattern.compile("""(\r?\n\s*){2,}""")

    // --- This method splits strings into a list of paragraphs
    fun split(docId: String, text: String): List<Paragraph> {
        if (text.isBlank())
            return emptyList()

        // Split by regex
        val rawParagraphs = text.split(PARAGRAPH_PATTERN.toRegex())

        // Transform splits into list of paragraphs
        val paragraphList = mutableListOf<Paragraph>()
        var orderIndex = 0

        for (paragraph in rawParagraphs) {
            // Sanitize lead and trailing blankspace
            val cleanText = paragraph.trim()

            if (cleanText.length > 5000) {
                println("[PARAGRAPH_SPLITTER]: WARNING: Paragraph too large (${cleanText.length} chars). Skipping to avoid crash...")
            }

            // We ignore empty strings or things shorter than 3 chars (like "A.")
            if (cleanText.isNotEmpty() && cleanText.length > 3) {
                paragraphList.add(
                    Paragraph(
                        docId = docId,
                        content = cleanText,
                        index = orderIndex++
                    )
                )
            }
        }

        return paragraphList
    }
}