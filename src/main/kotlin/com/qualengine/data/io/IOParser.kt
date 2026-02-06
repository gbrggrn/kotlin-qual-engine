package com.qualengine.data.io

import com.qualengine.data.model.TextBlock
import java.io.File

interface IOParser {
    // --- Parses file into lazy sequence of TextBlocks
    // --- Sequence: to only hold ONE (1) block in memory at a time!
    fun parse(file: File): Sequence<TextBlock>
}