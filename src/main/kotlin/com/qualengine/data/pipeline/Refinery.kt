package com.qualengine.data.pipeline

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.db.model.Documents
import com.qualengine.core.analysis.SanityStatus
import com.qualengine.data.client.OllamaClient
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.db.model.Sentences
import com.qualengine.data.model.Paragraph
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID

object Refinery {
    // Config
    private val MODEL_TOKEN_LIMIT = 8000
    private val SENTENCE_TOKEN_LIMIT = 200

    // Dependencies
    private val ollamaClient = DependencyRegistry.ollamaClient
    private val ollamaEnricher = DependencyRegistry.ollamaEnricher
    private val semanticCompressor = DependencyRegistry.semanticCompressor
    private val paragraphSplitter = DependencyRegistry.paragraphSplitter
    private val sentenceSplitter = DependencyRegistry.sentenceSplitter
    private val sanityFilter = DependencyRegistry.sanityFilter
    private val vectorMath = DependencyRegistry.vectorMath

    fun ingestFile (file: File, onProgress: (Double, String) -> Unit) {
        // --- Read file content
        onProgress(-1.0, "Reading file...")
        val rawText = file.readText()
        val docId = UUID.randomUUID().toString()

        // --- Greate global anchor (vectorize document)
        // TOKEN_AMOUNT: nomic-embed-text optimized (8192 token window)
        onProgress(-1.0, "Generating global context...")
        val docVector = ollamaClient.getVector(rawText, MODEL_TOKEN_LIMIT)

        // --- Save global anchor to database (document)
        saveDocument(docId, file, rawText, docVector)

        // --- Send to paragraph processor (which will send it to sentence processor)
        processParagraphs(docId, rawText, docVector, onProgress)

        onProgress(1.0, "Done. Document Hierarchy stored")
    }

    fun processParagraphs(
        docId: String,
        rawText: String,
        docVector: DoubleArray,
        onProgress: (Double, String) -> Unit
    ) {
        val paragraphs = paragraphSplitter.split(docId, rawText)
        val total = paragraphs.size
        var previousContext = ""
        var lastReportedProgress = 0.0

        paragraphs.forEachIndexed { index, p ->
            // --- Apply sanity filter
            val status = sanityFilter.evaluate(p.content)
            if (status == SanityStatus.NOISE)
                return@forEachIndexed

            // --- Enrich
            val compressed = semanticCompressor.compress(previousContext)
            val enrichedText = ollamaEnricher.enrichParagraph(p.content, compressed)
            previousContext = enrichedText

            // --- Vectorize and blend
            val rawVector = ollamaClient.getVector(enrichedText, MODEL_TOKEN_LIMIT)
            val finalVector =
                if (rawVector.isNotEmpty() && status == SanityStatus.CLEAN) {
                    vectorMath.blend(rawVector, docVector)
                } else {
                    rawVector // Leave raw vector for quarantine or empty cases
                }

            // --- Save paragraph to database
            if (finalVector.isNotEmpty()) {
                saveParagraph(p, finalVector, status)
                // --- Break down CLEAN paragraphs into sentences
                if (status == SanityStatus.CLEAN) {
                    processSentences(p.id, docId, p.content)
                }
            }

            // --- Update progress
            val currentProgress = (index + 1).toDouble() / total.toDouble()
            if (currentProgress - lastReportedProgress > 0.01 || index == total - 1) {
                onProgress(currentProgress, "Processing ${index + 1} / $total")
                lastReportedProgress = currentProgress
            }
        }
    }

    private fun processSentences(paragraphId: String, docId: String, paragraphContent: String) {
        val sentences = sentenceSplitter.split(docId, paragraphId, paragraphContent)

        transaction {
            for (s in sentences) {
                // Vectorize each sentence individually
                val sentenceVector = ollamaClient.getVector(s.content, SENTENCE_TOKEN_LIMIT) // Smaller window for speed

                Sentences.insert {
                    it[this.id] = s.id
                    it[this.docId] = s.docId
                    it[this.paragraphId] = s.paragraphId
                    it[this.content] = s.content
                    it[this.vector] = sentenceVector.joinToString(",")
                    it[this.index] = s.index
                    it[status] = SanityStatus.CLEAN.name
                }
            }
        }
    }

    private fun saveDocument(docId: String, file: File, rawText: String, vector: DoubleArray) {
        transaction {
            Documents.insert {
                it[this.id] = docId
                it[content] = rawText.take(200) + "..."
                it[origin] = file.name
                it[this.vector] = vector.joinToString(",")
                it[timestamp] = System.currentTimeMillis()
            }
        }
    }

    private fun saveParagraph(p: Paragraph, finalVector: DoubleArray, status: SanityStatus) {
        transaction {
            Paragraphs.insert {
                it[this.id] = p.id
                it[this.docId] = p.docId
                it[content] = p.content
                it[index] = p.index
                it[this.vector] = finalVector.joinToString(",")
                it[this.status] = status.name
            }
        }
    }

    /*fun ingestFile2 (file: File, onProgress: (Double, String) -> Unit) {
        onProgress(-1.0, "Reading file...")

        // Read file content
        // TODO: Upgrade to parse different file types later
        val rawText = file.readText()
        val docId = UUID.randomUUID().toString()

        // Register parent document in db
        transaction {
            Documents.insert {
                it[this.id] = docId
                it[content] = rawText.take(100) + "..." // Keep for preview
                it[origin] = file.name
                it[timestamp] = System.currentTimeMillis()
            }
        }
        onProgress(-1.0, "Splitting...")

        // Split by paragraphs
        val paragraphs = ParagraphSplitter.split(docId, rawText)
        val total = paragraphs.size
        onProgress(0.0, "Enriching & Vectorizing $total paragraphs...")

        // Context "memory" cache
        var previousContext = ""

        // Enrich/vectorize loop
        var count = 0 // Count to display number of paragraphs processed

        var lastReportedProgress = 0.0

        for ((index, paragraph) in paragraphs.withIndex()) {
            //Chill out a bit to not completely overwhelm the app thread
            Thread.sleep(100)

            // Run "Why use many words when few do trick"-compressor on previous context
            val compressedContext = SemanticCompressor.compress(previousContext)

            // Send text to enricher for semantic summarization
            val enrichedText = OllamaEnricher.enrichParagraph(paragraph.content, compressedContext)

            // Update "memory" cache
            previousContext = enrichedText

            // Vectorize the semantic summarization
            val vector = OllamaClient.getVector(enrichedText)

            if (vector.isNotEmpty()){
                transaction {
                    Paragraphs.insert {
                        it[Paragraphs.id] = paragraph.id
                        it[Paragraphs.docId] = paragraph.docId
                        it[Paragraphs.content] = paragraph.content
                        it[Paragraphs.index] = paragraph.index
                        it[Paragraphs.vector] = vector.joinToString(",")
                    }
                }

                // Nudge the garbage collector every few loops
                if (index % 50 == 0) {
                    System.gc()
                }

                count ++
                // Calculate progress
                val currentProgress = (index + 1).toDouble() / total.toDouble()
                val diff = currentProgress - lastReportedProgress

                if (diff > 0.01 || index == total -1) {
                    onProgress(currentProgress, "Processed $count / $total")
                    lastReportedProgress = currentProgress
                }
            }
        }
        onProgress(1.0, "Done. Stored $count smart paragraph molecules...")
    }*/
}