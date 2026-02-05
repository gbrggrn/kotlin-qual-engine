package com.qualengine.data.db.model

import org.jetbrains.exposed.sql.Table

object Documents : Table() {
    val id = varchar("id", 50)
    val content = text("content")
    val origin = varchar("source", 255)
    val vector = text("vector").nullable()
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

object Sentences : Table() {
    val id = varchar("id", 50)
    val docId = reference("doc_id", Documents.id)
    val content = text("content")
    val index = integer("index")
    val vector = text("vector").nullable()

    val paragraphId = reference("paragraph_id", Paragraphs.id).nullable()
    val status = varchar("status", 20).default("CLEAN")

    override val primaryKey = PrimaryKey(id)
}

object Paragraphs : Table() {
    val id = varchar("id", 50)
    val docId = reference("doc_id", Documents.id)
    val content = text("content")
    val index = integer("index")
    val vector = text("vector").nullable()

    val status = varchar("status", 20).default("CLEAN")
    val sanityScore = double("sanityScore").default(1.0)

    override val primaryKey = PrimaryKey(id)
}