package com.qualengine.data.model

data class TextBlock(
    val rawText: String,
    val rawLength: Int = rawText.length,
    val sourceFileName: String = ""
)
