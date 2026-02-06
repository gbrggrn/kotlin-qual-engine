package com.qualengine.core.analysis

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.TextBlock

object ThematicSplitter {
    // Dependencies
    private val ollamaClient = DependencyRegistry.ollamaClient
    private val vectorMath = DependencyRegistry.vectorMath
    private val sentenceSplitter = DependencyRegistry.sentenceSplitter

    // Config
    private const val MIN_CHUNK_SIZE = 1000
    private const val SENTENCE_TOKEN_LIMIT = 200

    fun attemptSplit(block: TextBlock, docId: String): List<TextBlock> {
        // --- Split into sentences
        val sentences = sentenceSplitter.split(docId, "temp_split_id", block.rawText)

        // No split if there are too few sentences
        if (sentences.size < 4)
            return listOf(block)

        // --- Vectorize sentences (memory heavy)
        val vectors = sentences.map { s ->
            ollamaClient.getVector(s.content, SENTENCE_TOKEN_LIMIT)
        }

        // --- Scan for potential split-points
        // Basically looking for "fault lines" or the deepest semantic valley between sentences.
        var lowestSimilarity = 1.0
        var splitIndex = -1
        val buffer = 1 // Minimum of sentences per chunk

        for (i in buffer until sentences.size - buffer) {
            val vecA = vectors[i]
            val vecB = vectors[i + 1]
            val magA = vectorMath.getMagnitude(vecA)
            val magB = vectorMath.getMagnitude(vecB)

            // Calculate cosine distance between Sentence[i] and Sentence[i+1] (similarity)
            val similarity = vectorMath.calculateCosineDistance(vecA, magA, vecB, magB)

            if (similarity < lowestSimilarity) {
                lowestSimilarity = similarity
                splitIndex = i
            }
        }

        // --- Evaluate the split points
        // If similarity is still high (coherent text), split it anyway to respect token limits
        val threshold = if (block.rawText.length > 3000)
            0.85 else 0.65

        if (splitIndex != -1 && lowestSimilarity < threshold) {
            // --- Sentences 0 to splitIndex
            val textA = sentences.slice(0..splitIndex).joinToString(" ") { it.content }

            // --- Sentences splitIndex + 1 to end
            val overlap = sentences[splitIndex].content
            val textB = overlap + " " + sentences.slice(splitIndex + 1 until sentences.size).joinToString(" ") { it.content }

            // --- Return the split TextBlock in two parts
            return listOf(
                TextBlock(textA, sourceFileName = block.sourceFileName),
                TextBlock(textB, sourceFileName = block.sourceFileName)
            )
        }
        // Failed to find a proper "fault line" by which to split the block
        return listOf(block)
    }
}