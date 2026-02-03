package com.qualengine.core.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

object OllamaEnricher {

    private val client = HttpClient.newHttpClient()

    // Change model here - phi3 as standard to be more lightweight
    private const val MODEL_NAME = "phi3"
    private const val OLLAMA_URL = "http://localhost:11434/api/generate"

    /**
     * Takes the current paragraph and the previous one (context).
     * Returns a rewritten version that is standalone and explicit.
     */
    fun enrichParagraph(current: String, previous: String): String {
        // Cheap filter to reduce load on llm
        val vagueTriggers = listOf("it ", "this ", "that ", "they ", "he ", "she ", "the system", "the error")
        val needsHelp = vagueTriggers.any { current.lowercase().contains(it) }
        if (!needsHelp || current.length > 200) return current

        // THE LOBOTOMIZED PROMPT!
        // Phi-3 follows the <|user|> ... <|assistant|> pattern strictly.
        // Trick it by starting the assistant's response for it with "Rewritten: "
        val prompt = """
            <|user|>
            Task: Rewrite the Target text to be specific. Resolve "it", "this", "they" using the Context.
            Rules: NO filler. NO "Here is". NO quotes. ONE sentence only.
            
            Context: $previous
            Target: $current
            <|end|>
            <|assistant|>
        """.trimIndent()

        val options = JSONObject()
        options.put("num_predict", 60)   // Hard limit: Stop after 60 tokens (approx 40 words)
        options.put("temperature", 0.0)  // 0.0 = Lobotomized mode (No creativity)
        options.put("stop", JSONArray().put("\n").put("<|end|>")) // STOP immediately at a new line

        val json = JSONObject()
        json.put("model", MODEL_NAME)
        json.put("prompt", prompt)
        json.put("stream", false)
        json.put("options", options)
        json.put("keep_alive", "5m") // Keep model in RAM for 5 mins - first prompt slow and the rest fast

        val request = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
            .timeout(Duration.ofSeconds(60)) // Hard timeout if too slow execution (10s)
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()

            if (response.statusCode() != 200) {
                println("[Enricher Error] Status ${response.statusCode()}")
                return current
            }

            val responseJson = JSONObject(body)
            if (!responseJson.has("response")) return current

            // Clean result
            responseJson.getString("response").trim().removeSurrounding("\"")

        } catch (e: HttpTimeoutException) {
            println("[Enricher Timeout]: AI took too long. Skipping.")
            current
        } catch (e: Exception) {
            println("[Enricher Exception]: ${e.message}")
            current
        }
    }

    fun summarizeCluster(snippets: List<String>) : String {
        if (snippets.isEmpty())
            return "Unknown cluster."

        // Read 5 paragraphs, shuffle to get representative data
        val sample = snippets.shuffled().take(5).joinToString("\n-")

        val prompt = """
            <|user|>
            Task: Identify the specific THEME of these snippets.
            Output format: A short, punchy category label
            CONSTRAINT: Output strictly 1 - 3 words
            
            - DO NOT USE NUMBERING
            - DO NOT WRITE FULL SENTENCES
            - DO NOT USE QUOTES
            - DO NOT USE PUNCTUATION
            - DO NOT USE "and"
            - NO FILLER WORDS
            
            GOOD Examples:
            - "Database Latency Issues"
            - "Frontend Memory Leaks"
            - "Staff Burnout"
            - "Q3 Budget Cuts"

            Snippets:
            - $sample
            
            Theme Label:
            <|end|>
            <|assistant|>
        """.trimIndent()

        val options = JSONObject()
        options.put("num_predict", 10)
        options.put("temperature", 0.3)
        options.put("stop", JSONArray().put("\n").put("<|end|>"))

        val json = JSONObject()
        json.put("model", MODEL_NAME)
        json.put("prompt", prompt)
        json.put("stream", false)
        json.put("options", options)
        json.put("keep_alive", "5m")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(OLLAMA_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
            .timeout(Duration.ofSeconds(30))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val responseJson = JSONObject(response.body())

            if (responseJson.has("response")) {
                var text = responseJson.getString("response").trim()
                // Cleanup
                text = text
                    .removePrefix("Title:")
                    .removePrefix("Label:")
                    .removePrefix("-").trim()
                text.removeSurrounding("\"")
            } else {
                "Cluster"
            }
        } catch (e: Exception) {
            println("[Summarizer error]: ${e.message}")
            "Cluster (Error)"
        }
    }
}

// Test runner for OllamaEnricher
fun main() {
    println("Testing Contextual Proxy...")

    val context = "The legacy SQL database suffers from connection pool exhaustion during peak hours."
    val vagueTarget = "It often crashes because of this, causing the frontend to freeze."

    println("\n--- INPUT ---")
    println("CONTEXT: $context")
    println("TARGET:  $vagueTarget")

    val result = OllamaEnricher.enrichParagraph(vagueTarget, context)

    println("\n--- RESULT (The Proxy) ---")
    println(result)

    println("\n-----------------------------")
    println("If the result explicitly mentions 'SQL database' or 'connection pool', the experiment is a SUCCESS.")
}