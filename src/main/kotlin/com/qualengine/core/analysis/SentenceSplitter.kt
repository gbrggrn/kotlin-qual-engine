package com.qualengine.core.analysis

import com.qualengine.data.model.Sentence
import java.util.UUID

object SentenceSplitter {

    private val SENTENCE_PATTERN = Regex("(?<=[.!?])\\s+")

    fun split(docId: String, paragraphId: String, text: String): List<Sentence> {
        if (text.isBlank())
            return emptyList()

        // Split by pattern
        val rawSentences = text.split(SENTENCE_PATTERN)
        val sentenceList = mutableListOf<Sentence>()
        var orderIndex = 0

        for (s in rawSentences) {
            val cleanText = s.trim()

            if (cleanText.length > 3) {
                sentenceList.add(
                    Sentence(
                        id = UUID.randomUUID().toString(),
                        docId = docId,
                        paragraphId = paragraphId,
                        content = cleanText,
                        index = orderIndex++
                    )
                )
            }
        }
        return sentenceList
    }
}