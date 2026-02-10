package com.qualengine.data.db

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.db.model.Documents
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.db.model.Sentences
import com.qualengine.data.model.VectorPoint
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    private val vectorMath = DependencyRegistry.vectorMath

    fun init() {
        val dbFile = File("qualengine.db")

        Database.Companion.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction {
            // Check tables exist - otherwise CREATE
            SchemaUtils.create(Documents, Sentences, Paragraphs)
        }

        println("Refinery Storage: ONLINE [$dbFile.absolutePath]")
    }

    /**
     * Helper: Converts a database string like "[0.1, 0.2]" into a DoubleArray.
     * Uses manual string manipulation which is faster than Regex/JSON for this specific format.
     */
    private fun parseEmbedding(dbString: String): DoubleArray {
        // Clean up brackets if they exist
        val clean = dbString.trim().removeSurrounding("[", "]")

        if (clean.isBlank()) return DoubleArray(0)

        // Split by comma and convert
        // .map { it.toDouble() } creates a List, so we convert to Array at the end
        try {
            return clean.split(",").map { it.trim().toDouble() }.toDoubleArray()
        } catch (e: NumberFormatException) {
            println("Error parsing vector: ${e.message}")
            return DoubleArray(0)
        }
    }

    fun getParagraphPoints(): List<VectorPoint> {
        return transaction {
            Paragraphs.selectAll().map {
                val rawString = it[Paragraphs.vector] ?: ""
                val vectorArray = parseEmbedding(rawString)
                VectorPoint(
                    id = it[Paragraphs.id],
                    embedding = vectorArray,
                    metaData = it[Paragraphs.content],
                    layer = 2,
                    parentId = it[Paragraphs.docId]
                )
            }
        }
    }

    fun getSentencesForParagraphs(paraIds: List<String>): List<VectorPoint> {
        return transaction {
            Sentences.selectAll().where { Sentences.paragraphId inList paraIds }.map {
                val rawString = it[Paragraphs.vector] ?: ""
                VectorPoint(
                    id = it[Sentences.id],
                    embedding = parseEmbedding(rawString),
                    metaData = it[Sentences.content],
                    layer = 1,
                    parentId = it[Sentences.paragraphId]
                )
            }
        }
    }

    fun getParagraphsForDocs(docIds: List<String>): List<VectorPoint> {
        return transaction {
            Paragraphs.selectAll().where { Paragraphs.docId inList docIds }.map {
                val rawString = it[Paragraphs.vector] ?: ""
                VectorPoint(
                    id = it[Paragraphs.id],
                    embedding = parseEmbedding(rawString),
                    metaData = it[Paragraphs.content],
                    layer = 2,
                    parentId = it[Paragraphs.docId]
                )
            }
        }
    }

    // --- Fetch all documents as points
    fun getDocumentPoints(): List<VectorPoint> {
        return transaction {
            Documents.selectAll().map {
                val rawString = it[Documents.vector] ?: ""
                VectorPoint(
                    id = it[Documents.id],
                    embedding = parseEmbedding(rawString),
                    metaData = it[Documents.origin],
                    layer = 3, // Layer 3 is Document Level
                    parentId = null
                )
            }
        }
    }

    // --- Fetch the top 20 results that are semantically closest to the query vector (a vectorized search query)
    fun searchParagraphs(queryVector: DoubleArray, topK: Int = 20): List<VectorPoint> {
        val all = getParagraphPoints()
        return all.map { point ->
            val magQueryVector = vectorMath.getMagnitude(queryVector)
            val magPointVector = vectorMath.getMagnitude(point.embedding)
            val score = vectorMath.calculateCosineDistance(queryVector, magQueryVector, point.embedding, magPointVector)
            point to score
        }
            .sortedBy { it.second }
            .take(topK)
            .map { it.first }
    }
}