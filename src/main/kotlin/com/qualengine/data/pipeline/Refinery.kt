package com.qualengine.data.pipeline

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.db.model.Documents
import com.qualengine.core.analysis.SanityStatus
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.db.model.Sentences
import com.qualengine.data.model.TextBlock
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID

object Refinery {
    // Config
    private const val MODEL_TOKEN_LIMIT = 8000
    private const val DOC_SAMPLE_SIZE = 10000
    private const val SENTENCE_TOKEN_LIMIT = 200

    // Dependencies
    private val ollamaClient = DependencyRegistry.ollamaClient
    private val ollamaEnricher = DependencyRegistry.ollamaEnricher
    private val semanticCompressor = DependencyRegistry.semanticCompressor
    private val sentenceSplitter = DependencyRegistry.sentenceSplitter
    private val sanityFilter = DependencyRegistry.sanityFilter
    private val vectorMath = DependencyRegistry.vectorMath
    private val thematicSplitter = DependencyRegistry.thematicSplitter
    private val textParser = com.qualengine.data.io.implementations.TextFileParser

    fun ingestFile (file: File, onProgress: (Double, String) -> Unit) {
        // --- Prep
        onProgress(-1.0, "Initializing stream...")
        val docId = UUID.randomUUID().toString()

        // --- Generate global context
        // MODEL_TOKEN_LIMIT: nomic-embed-text optimized (8192 token window)
        onProgress(-1.0, "Generating global context...")
        val docSample = file.bufferedReader().use { reader ->
            val buffer = CharArray(DOC_SAMPLE_SIZE)
            val read = reader.read(buffer)
            if (read > 0)
                String(buffer, 0, read)
            else
                ""
        }

        onProgress(-1.0, "Vectorizing global context...")
        val docVector = ollamaClient.getVector(docSample, MODEL_TOKEN_LIMIT)

        // --- Save global (document) context
        saveDocument(docId, file, docSample, docVector)

        // --- Process the stream
        processStream(docId, docVector, file, onProgress)

        onProgress(1.0, "Done. Document Hierarchy stored")
    }

    fun processStream(docId: String, docVector: DoubleArray, file: File, onProgress: (Double, String) -> Unit) {
        var previousContext = ""
        var blockIndex = 0

        // Linked list queue so that split big chunks can fit back in where they were taken from
        val blockQueue = java.util.LinkedList<TextBlock>()
        // Initialize the queue with the raw stream
        val rawStream = textParser.parse(file).iterator()

        // --- TRIAGE LOOP ---
        while (rawStream.hasNext() || blockQueue.isNotEmpty()) {
            // Process queue (split) before pulling new raw block
            val currentBlock = if(blockQueue.isNotEmpty()) {
                blockQueue.removeFirst()
            } else {
                rawStream.next()
            }

            // --- STEP 1: SANITY FILTER ---
            // Extract micro blocks
            val sanity = sanityFilter.evaluate(currentBlock.rawText)
            if (sanity == SanityStatus.NOISE)
                continue // Discard noise immediately

            // -- STEP 2: SPLIT CHECK ---
            // Try to split large blocks semantically
            if (currentBlock.rawText.length > 1500) {
                onProgress(-1.0, "Splitting massive text block (${currentBlock.rawText.length} chars)")

                val splitResult = thematicSplitter.attemptSplit(currentBlock, docId)

                // If split was performed we bush the blocks back into the queue
                // If split failed (block was returned) -> force process to avoid infinite loop
                if (splitResult.size > 1) {
                    // Add to front of queue to maintain document order
                    splitResult.asReversed().forEach { blockQueue.addFirst(it) }
                    continue // Loop back to process the newly split, smaller text blocks
                }
            }

            // -- STEP 3: ENRICH & VECTORIZE ---
            // Blocks are now clean and sized correctly
            val compressed = semanticCompressor.compress(previousContext)

            // Only enrich clean text. Quarantined blocks gets processed raw
            val enrichedText = if (sanity == SanityStatus.CLEAN) {
                ollamaEnricher.enrichParagraph(currentBlock.rawText, compressed)
            } else {
                currentBlock.rawText
            }

            // Update context chain
            previousContext = enrichedText

            // Vectorize
            val rawVector = ollamaClient.getVector(enrichedText, MODEL_TOKEN_LIMIT)

            if (rawVector.isNotEmpty()) {
                val finalVector = if (sanity == SanityStatus.CLEAN) {
                    vectorMath.blend(rawVector, docVector)
                } else {
                    rawVector
                }

                // --- SAVE ---
                val paragraphId = UUID.randomUUID().toString()
                saveBlockAsParagraph(paragraphId, docId, currentBlock.rawText, blockIndex++, finalVector, sanity)

                // Break down into sentences for quotes
                if (sanity == SanityStatus.CLEAN) {
                    processSentences(paragraphId, docId, currentBlock.rawText)
                }
            }
            if (blockIndex % 5 == 0)
                onProgress(-1.0, "Processed $blockIndex text blocks")
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

    private fun saveBlockAsParagraph(
        pId: String,
        dId: String,
        text: String,
        idx: Int,
        vec: DoubleArray,
        status: SanityStatus
    ) {
        transaction {
            Paragraphs.insert {
                it[this.id] = pId
                it[this.docId] = dId
                it[content] = text
                it[index] = idx
                it[this.vector] = vec.joinToString(",")
                it[this.status] = status.name
            }
        }
    }

    /*
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
     */
}