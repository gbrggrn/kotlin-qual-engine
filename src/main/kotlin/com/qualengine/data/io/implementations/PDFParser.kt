package com.qualengine.data.io.implementations

import com.qualengine.data.io.IOParser
import com.qualengine.data.model.TextBlock
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

object PDFParser : IOParser {
    override fun parse(file: File): Sequence<TextBlock> = sequence {
        // Setup temp file to not load the whole PDF into RAM
        // stream from disk
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val stripper = PDFTextStripper()

            // Set sorting off for the stripper to improve performance
            stripper.sortByPosition = false

            for (pageIndex in 1..document.numberOfPages) {
                // Config to read one page at a time
                stripper.startPage = pageIndex
                stripper.endPage = pageIndex

                // Extract text
                val pageText = stripper.getText(document).trim()

                if (pageText.isNotBlank()) {
                    yield(
                        TextBlock(
                            rawText = pageText,
                            sourceFileName = "${file.name} (Page $pageIndex)"
                        )
                    )
                }
            }
        }
    }
}