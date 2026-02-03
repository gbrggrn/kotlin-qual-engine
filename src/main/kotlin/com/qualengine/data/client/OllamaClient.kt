package com.qualengine.data.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object OllamaClient {
    // Ollama url
    private const val ENDPOINT = "http://localhost:11434/api/embeddings"

    private const val MODEL_NAME = "all-minilm"

    private val client = HttpClient.newHttpClient()

    fun getVector(text: String): List<Double> {
        val cleanText = text.replace("\"", "\\\"").replace("\n", " ")

        val jsonPayload = """ {
            "model": "$MODEL_NAME",
            "prompt": "$cleanText"
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

                return emptyList()
            }

            val body = response.body()

            val startMarker = "\"embedding\":["
            val startIndex = body.indexOf(startMarker)

            if (startIndex == -1)
                return emptyList()

            val contentStart = startIndex + startMarker.length
            val contentEnd = body.indexOf("]", contentStart)

            val vectorString = body.substring(contentStart, contentEnd)

            return vectorString.split(",").map {
                it.trim().toDouble()
            }
        } catch (e: Exception) {
            println("Connection failed - is Ollama running?")
            println("Error: ${e.message}")
            return emptyList()
        }
    }
}