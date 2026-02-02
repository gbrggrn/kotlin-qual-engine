package com.qualengine

import javafx.application.Application
import java.io.File

fun main() {

    fun generateTestFile() {
        val filename = "test_feedback_500.txt"
        val file = File(filename)

        // Theme 1: Facilities
        val facilities = listOf(
            "The coffee machine on the 3rd floor is leaking again.",
            "The air conditioning in the server room is making a weird rattling noise.",
            "We are completely out of paper towels in the kitchen.",
            "The elevator by the north entrance is stuck on the ground floor.",
            "Parking is a nightmare; we need more reserved spots for visitors."
        )

        // Theme 2: IT & Security
        val itSecurity = listOf(
            "I keep getting phishing emails from what looks like the CEO.",
            "The VPN disconnects every time I try to push code to Git.",
            "My laptop screen goes black whenever I open Zoom.",
            "We need to enforce 2FA on all internal admin panels immediately.",
            "The firewall is blocking access to the documentation portal."
        )

        // Theme 3: HR & Culture
        val hrCulture = listOf(
            "The new remote work policy is very confusing regarding Fridays.",
            "I still haven't received my reimbursement for the travel expenses.",
            "We need more diversity in the hiring pipeline for engineering.",
            "The onboarding process for new hires takes way too long.",
            "Can we please stop having meetings during lunch hours?"
        )

        // Theme 4: Product & UX
        val productUx = listOf(
            "The 'Submit' button on the checkout page is misaligned on mobile.",
            "Customers are complaining that the app crashes on launch.",
            "Dark mode text is unreadable on the settings screen.",
            "The search bar takes 5 seconds to return any results.",
            "We need to simplify the user registration flow, drop-off is high."
        )

        val allThemes = listOf(facilities, itSecurity, hrCulture, productUx)
        val sb = StringBuilder()

        println("Generating 500 paragraphs...")

        repeat(500) {
            val theme = allThemes.random()
            val sentence1 = theme.random()
            val sentence2 = theme.random() // Add a second sentence to make it "meatier"

            // Add random "Human Noise" to test your semantic compressor
            val intro = listOf("Basically,", "I think", "Just fyi,", "Report:").random()
            val noise = listOf("It's really annoying.", "Please fix.", "This is critical.").random()

            sb.append("$intro $sentence1 Also, $sentence2 $noise\n\n")
        }

        file.writeText(sb.toString())
        println("Created $filename at ${file.absolutePath}")
    }

    generateTestFile()
    Application.launch(QualEngineApp::class.java)
}