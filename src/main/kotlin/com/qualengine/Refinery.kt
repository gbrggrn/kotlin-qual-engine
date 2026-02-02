package com.qualengine

import com.qualengine.data.OllamaClient
import com.qualengine.data.OllamaEnricher
import com.qualengine.logic.SemanticCompressor
import com.qualengine.model.Documents
import com.qualengine.model.Moleculizer
import com.qualengine.model.Paragraphs
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID

object Refinery {

    fun ingestFile (file: File, onProgress: (Double, String) -> Unit) {
        onProgress(-1.0, "Reading file...")

        // Read file content
        // TODO: Upgrade to parse different file types later
        val rawText = file.readText()
        val docId = UUID.randomUUID().toString()

        // Register parent document in db
        transaction {
            Documents.insert{
                it[this.id] = docId
                it[content] = rawText.take(100) + "..." // Keep for preview
                it[origin] = file.name
                it[timestamp] = System.currentTimeMillis()
            }
        }
        onProgress(-1.0, "Moleculizing...")

        // Moleculize (split by paragraph)
        val molecules = Moleculizer.moleculize(docId, rawText)
        val total = molecules.size
        onProgress(0.0, "Enriching & Vectorizing $total paragraphs...")

        // Context "memory" cache
        var previousContext = ""

        // Enrich/vectorize loop
        var count = 0 // Count to display number of paragraphs processed

        var lastReportedProgress = 0.0

        for ((index, molecule) in molecules.withIndex()) {
            //Chill out a bit to not completely overwhelm the app thread
            Thread.sleep(50)

            // Run "Why use many words when few do trick"-compressor on previous context
            val compressedContext = SemanticCompressor.compress(previousContext)

            // Send text to enricher for semantic summarization
            val enrichedText = OllamaEnricher.enrichParagraph(molecule.content, previousContext)

            // Update "memory" cache
            previousContext = enrichedText

            // Vectorize the semantic summarization
            val vector = OllamaClient.getVector(enrichedText)

            if (vector.isNotEmpty()){
                transaction {
                    Paragraphs.insert {
                        it[Paragraphs.id] = molecule.id
                        it[Paragraphs.docId] = molecule.docId
                        it[Paragraphs.content] = molecule.content
                        it[Paragraphs.index] = molecule.index
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
    }
}