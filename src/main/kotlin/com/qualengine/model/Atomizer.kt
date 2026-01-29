package com.qualengine.model

import java.text.BreakIterator
import java.util.Locale

object Atomizer {
    fun atomize(docId: String, text: String): List<SentenceAtom>{
        // Return empty list if no text
        if (text.isBlank()) return emptyList()

        // Create iterator
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(text)

        // Create collection to hold sentences
        val atoms = mutableListOf<SentenceAtom>()
        // Set iterator start position
        var start = iterator.first()
        // Set iterator end position
        var end = iterator.last()
        // Set tracker index variable to 0
        var orderIndex = 0

        // Iterate over whole collection
        while (end != BreakIterator.DONE) {
            // Substring sentence
            val sentence = text.substring(start, end).trim()

            // Check if sentence is not empty and longer than 3 chars
            if (sentence.isNotEmpty() && sentence.length > 3){
                // Create "smart" atom that knows where it belongs
                val atom = SentenceAtom(
                    docId = docId,
                    content = sentence,
                    index = orderIndex++
                )

                // Add to sentence collection
                atoms.add(atom)
            }

            //Reset start to end and reset end to next
            start = end;
            end = iterator.next()
        }

        // Return the collection of sentences
        return atoms
    }
}