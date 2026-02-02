package com.qualengine.data

import com.qualengine.model.Documents
import com.qualengine.model.Sentences
import com.qualengine.model.Paragraphs
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

    fun init() {
        val dbFile = File("qualengine.db")

        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

        transaction{
            // Check tables exist - otherwise CREATE
            SchemaUtils.create(Documents, Sentences, Paragraphs)
        }

        println("Refinery Storage: ONLINE [$dbFile.absolutePath]")
    }
}