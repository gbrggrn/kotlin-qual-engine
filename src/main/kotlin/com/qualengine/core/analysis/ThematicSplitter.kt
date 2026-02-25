package com.qualengine.core.analysis

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.TextBlock

object ThematicSplitter {
    // Dependencies
    private val ollamaClient = DependencyRegistry.ollamaClient
    private val vectorMath = DependencyRegistry.vectorMath
    private val sentenceSplitter = DependencyRegistry.sentenceSplitter

    // Settings
    private const val MIN_SENTENCES_PER_CHUNK = 4
    private const val SENTENCE_TOKEN_LIMIT = 200
    private const val SPLIT_INDEX = -1
    private const val LOWEST_SIMILARITY_COSINE_ANGLE = 1.0
    private const val MAX_RAW_TEXT_LENGTH = 3000;

    fun attemptSplit(block: TextBlock, docId: String): List<TextBlock> {
        // === Split into sentences
        val sentences = sentenceSplitter.split(docId, "temp_split_id", block.rawText)

        // No split if there are too few sentences
        if (sentences.size < MIN_SENTENCES_PER_CHUNK)
            return listOf(block)

        // === Vectorize sentences (memory heavy)
        val vectors = sentences.map { s ->
            ollamaClient.getVector(s.content, SENTENCE_TOKEN_LIMIT)
        }

        // === Scan for potential split-points
        // Basically looking for "fault lines" or the deepest semantic valley between sentences.
        var lowestSimilarity = LOWEST_SIMILARITY_COSINE_ANGLE
        var splitIndex = SPLIT_INDEX
        val buffer = 1

        for (i in buffer until sentences.size - buffer) {
            val vecA = vectors[i]
            val vecB = vectors[i + 1]
            val magA = vectorMath.getMagnitude(vecA)
            val magB = vectorMath.getMagnitude(vecB)

            // Calculate cosine distance between Sentence[i] and Sentence[i+1] (similarity)
            val similarity = vectorMath.calculateCosineSimilarity(vecA, magA, vecB, magB)

            if (similarity < lowestSimilarity) {
                lowestSimilarity = similarity
                splitIndex = i
            }
        }

        // === Evaluate the split points
        // If similarity is still high (coherent text), split it anyway to respect token limits
        val threshold = if (block.rawText.length > MAX_RAW_TEXT_LENGTH)
            0.85 else 0.65

        if (splitIndex != -1 && lowestSimilarity < threshold) {
            // Sentences 0 to splitIndex
            val textA = sentences.slice(0..splitIndex).joinToString(" ") { it.content }

            // Sentences splitIndex + 1 to end
            val overlap = sentences[splitIndex].content
            val textB = overlap + " " + sentences.slice(splitIndex + 1 until sentences.size).joinToString(" ") { it.content }

            // Return the split TextBlock in two parts
            return listOf(
                TextBlock(textA, sourceFileName = block.sourceFileName),
                TextBlock(textB, sourceFileName = block.sourceFileName)
            )
        }
        // Failed to find a proper "fault line" by which to split the block
        return listOf(block)
    }
}