package com.qualengine.data.db

import com.qualengine.data.db.model.Documents
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.db.model.Sentences
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
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
}