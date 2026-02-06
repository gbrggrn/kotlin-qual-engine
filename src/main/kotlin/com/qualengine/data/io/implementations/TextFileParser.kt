package com.qualengine.data.io.implementations

import com.qualengine.data.io.IOParser
import com.qualengine.data.model.TextBlock
import java.io.File

object TextFileParser : IOParser {
    override fun parse(file: File): Sequence<TextBlock> = sequence {
        file.useLines { lines ->
            val buffer = StringBuilder()

            for(line in lines) {
                if (line.isBlank()) {
                    // --- Line break means split ---
                    if (buffer.isNotEmpty()) {
                        val finalBlock = buffer.toString().trim()
                        if (finalBlock.isNotEmpty()) {
                            yield(TextBlock(finalBlock, sourceFileName = file.name))
                        }
                        buffer.clear()
                    }
                } else {
                    // --- Content accumulation ---
                    if (buffer.isNotEmpty()) {
                        buffer.append(" ")
                    }
                    buffer.append(line.trim())
                }
            }
            // --- Final flush ---
            // If file ends without a newline: yield what is left of it
            if (buffer.isNotEmpty()) {
                val finalBlock = buffer.toString().trim()
                if (finalBlock.isNotEmpty()) {
                    yield(TextBlock(finalBlock, sourceFileName = file.name))
                }
            }
        }
    }

}