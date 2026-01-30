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
    fun ingestFile (file: File, onProgress: (String) -> Unit) {
        onProgress("Reading ${file.name}")

        // FAKE - REMEMBER: BUILD A FUNCTIONING CSV PARSER LATER!!!
        val rawText = file.readText()
        val docId = UUID.randomUUID().toString()

        transaction {
            Documents.insert{
                it[this.id] = docId
                it[content] = rawText.take(100) + "..."
                it[origin] = file.name
                it[timestamp] = System.currentTimeMillis()
            }
        }
        onProgress("Processing ${file.name}")

        val atoms = Atomizer.atomize(docId, rawText)
        onProgress("Created ${atoms.size} atoms. Starting vectorization (may take some time)...")

        var count = 0
        for (atom in atoms) {
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
                // STATUS BAR LATER???
                count ++
                if (count % 5 == 0)
                    onProgress("Processed $count / ${atoms.size}...")
            }
        }
        onProgress("Refinery finished. $count sentences stored")
    }
}