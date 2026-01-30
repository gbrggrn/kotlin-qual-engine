package com.qualengine

import com.qualengine.data.OllamaClient
import com.qualengine.model.Atomizer
import com.qualengine.model.Documents
import com.qualengine.model.Sentences
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
        onProgress(-1.0, "Atomizing...")

        // Atomize (split by sentence)
        val atoms = Atomizer.atomize(docId, rawText)
        val total = atoms.size
        onProgress(0.0, "Vectorizing $total sentences...")

        // Add SentenceAtoms to DB
        var count = 0
        for ((index, atom) in atoms.withIndex()) {
            val vector = OllamaClient.getVector(atom.content)

            if (vector.isNotEmpty()){
                transaction {
                    Sentences.insert {
                        it[Sentences.id] = atom.id
                        it[Sentences.docId] = atom.docId
                        it[Sentences.content] = atom.content
                        it[Sentences.index] = atom.index
                        it[Sentences.vector] = vector.joinToString(",")
                    }
                }
                count ++
                // Calculate progress
                val progress = (index + 1).toDouble() / total.toDouble()
                    onProgress(progress, "Processed $count / $total...")
            }
        }
        onProgress(1.0, "Done. Stored $count sentences.")
    }
}