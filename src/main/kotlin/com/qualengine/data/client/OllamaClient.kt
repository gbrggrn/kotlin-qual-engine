package com.qualengine.data.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object OllamaClient {
    // Ollama url
    private const val ENDPOINT = "http://localhost:11434/api/embeddings"

    private const val MODEL_NAME = "nomic-embed-text"

    private val client = HttpClient.newHttpClient()

    fun getVector(text: String, tokenAmount: Int): DoubleArray {
        val cleanText = sanitizeForJson(text)

        val safePrompt = if (cleanText.length > tokenAmount) {
            cleanText.take(tokenAmount) + "..."
        } else {
            cleanText
        }

        val jsonPayload = """ {
            "model": "$MODEL_NAME",
            "prompt": "$safePrompt"
            }""".trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                println("Ollama error! Code: ${response.statusCode()}")
                println("Response body: ${response.body()}")
                return DoubleArray(0)
            }

            val body = response.body()
            val startMarker = "\"embedding\":["
            val startIndex = body.indexOf(startMarker)
            if (startIndex == -1)
                return DoubleArray(0)

            val contentStart = startIndex + startMarker.length
            val contentEnd = body.indexOf("]", contentStart)
            val vectorString = body.substring(contentStart, contentEnd)

            // --- Convert to DoubleArray
            return vectorString
                .split(",")
                .map { it.trim().toDouble() }
                .toDoubleArray()
        } catch (e: Exception) {
            println("Connection failed - is Ollama running?")
            println("Error: ${e.message}")
            return DoubleArray(0)
        }
    }

    private fun sanitizeForJson(input: String): String {
        return input
            .replace("\\", "\\\\") // Escape backslashes first!
            .replace("\"", "\\\"") // Escape quotes
            .replace("\n", "\\n")  // Escape newlines
            .replace("\r", "\\r")  // Escape carriage returns
            .replace("\t", "\\t")  // Escape tabs
    }
}