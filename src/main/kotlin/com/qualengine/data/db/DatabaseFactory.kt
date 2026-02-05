package com.qualengine.data.db

import com.qualengine.core.math.VectorMath
import com.qualengine.data.db.model.Documents
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.db.model.Sentences
import com.qualengine.data.model.VectorPoint
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

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
     * Fetches all rows and converts them directly into the domain model.
     */
    fun getAllVectorPoints(): List<VectorPoint> {
        return transaction {
            // Assuming your table object is named 'Paragraphs'
            Paragraphs.selectAll().map { row ->

                // 1. Parse the Embedding (String -> DoubleArray)
                // Stored in DB as: "[0.012, -0.34, ...]"
                val rawString = row[Paragraphs.vector] ?: ""
                val vectorArray = parseEmbedding(rawString)

                // 2. Create the Clean Object
                VectorPoint(
                    id = row[Paragraphs.id],
                    embedding = vectorArray,
                    metaData = row[Paragraphs.content], // The text snippet

                    // Defaults (will be calculated later by pipeline)
                    projectedX = 0.0,
                    projectedY = 0.0,
                    clusterId = -1,
                    layer = 2,
                    parentId = null
                )
            }
        }
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

    fun getSentencePoints(parentId: String? = null): List<VectorPoint> {
        return transaction {
            val query =
                if (parentId == null) {
                Sentences.selectAll().where { Sentences.paragraphId eq parentId }
            } else {
                Sentences.selectAll()
                }

            query.map {
                VectorPoint(
                    id = it[Sentences.id],
                    embedding = DoubleArray(0), // TODO: Change when sentence vectorization is active
                    metaData = it[Sentences.content],
                    layer = 1,
                    parentId = it[Sentences.paragraphId]
                )
            }
        }
    }

    // --- Fetch all documents + their origin
    fun getDocumentPoints(): List<Pair<String, String>> {
        return transaction {
            Documents.selectAll().map { it[Documents.id] to it[Documents.origin] }
        }
    }

    // --- Fetch the top 20 results that are semantically closest to the query vector (a vectorized search query)
    fun searchParagraphs(queryVector: DoubleArray, topK: Int = 20): List<VectorPoint> {
        val all = getParagraphPoints()
        return all.map { point ->
            val magQueryVector = VectorMath.getMagnitude(queryVector)
            val magPointVector = VectorMath.getMagnitude(point.embedding)
            val score = VectorMath.calculateCosineDistance(queryVector, magQueryVector, point.embedding, magPointVector)
            point to score
        }
            .sortedBy { it.second }
            .take(topK)
            .map { it.first }
    }
}