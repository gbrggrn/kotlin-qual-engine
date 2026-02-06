package com.qualengine.data.io.implementations

import com.qualengine.data.io.IOParser
import com.qualengine.data.model.TextBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream

object DocxParser : IOParser {
    override fun parse(file: File): Sequence<TextBlock> = sequence {
        FileInputStream(file).use { fis ->
            // Load the whole XML structure
            val document = XWPFDocument(fis)

            // Iterate through native word paragraphs
            for (paragraph in document.paragraphs) {
                val text = paragraph.text.trim()

                if (text.isNotBlank()) {
                    yield(
                        TextBlock(
                            rawText = text,
                            sourceFileName = file.name
                        )
                    )
                }
            }
            // Close XWPFDocument explicitly to be safe
            document.close()
        }
    }
}