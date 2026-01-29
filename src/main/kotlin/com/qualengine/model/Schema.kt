package com.qualengine.qualengine.model

import org.jetbrains.exposed.sql.Table

object Documents : Table() {
    val id = varchar("id", 50)

    val content = text("content")

    val source = varchar("source", 255)

    val timestamp = long("timestamp")

    // Primary key id
    override val primaryKey = PrimaryKey(id)
}

object Sentences : Table() {
    val id = varchar("id", 50)

    // Foreign key Document.id
    val docId = reference("doc_id", Documents.id)

    val content = text("content")

    // Blobs later??
    val vector = text("vector").nullable()

    override val primaryKey = PrimaryKey(id)
}